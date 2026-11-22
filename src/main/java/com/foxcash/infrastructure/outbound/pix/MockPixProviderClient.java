package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacao mock do provedor Pix para desenvolvimento.
 *
 * <p>Gera uma referencia externa (formato endToEndId do Bacen), um Pix
 * Copia e Cola estruturalmente plausivel (BR Code) e o QR Code em base64.
 * Em producao, substituir por um adaptador real (ex.: Gerencianet, Celcoin,
 * Pagar.me) que chama a API do PSP.
 *
 * <p><b>Idempotência (Spec 28):</b> re-enviar {@code createCharge} com o mesmo
 * {@code transactionId} (o {@code code} enviado ao Pagar.me) retorna a MESMA
 * cobrança — reproduz a idempotência do provedor real. Com
 * {@code foxcash.psp.mock-timeout=true}, a PRIMEIRA tentativa por transação
 * lança {@link PixProviderClient.PixProviderTimeoutException} (resposta incerta)
 * e o retry com o mesmo transactionId passa.
 *
 * <p><b>Bean sempre (Spec 91):</b> registrado SEMPRE — o {@code PixProviderRouter}
 * decide a ordem (mesmo padrão do payout na Spec 92). A simulação por config
 * ({@code mock-timeout}) só se aplica ao provedor {@code "mock"} (o A do E2E);
 * instâncias extras (ex.: {@code "suitpay"} — 2º adquirente do E2E de fallback)
 * nunca simulam falha por config. O {@link Simulation} é o controle DINÂMICO
 * (endpoint dev {@code /internal/psp-mock}) usado pelo E2E da Spec 91.
 */
@Component
public class MockPixProviderClient implements PixProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MockPixProviderClient.class);

    /** Simulação dinâmica do mock (Spec 91 — endpoint dev {@code /internal/psp-mock}). */
    public enum Simulation {
        /** Comportamento real do mock (config estática aplica apenas ao "mock"). */
        NORMAL,
        /** createCharge lança 5xx simulado (adquirente fora do ar — resposta incerta). */
        FAILURE,
        /** createCharge lança timeout SEM criar (1ª tentativa por transação). */
        TIMEOUT,
        /** createCharge CRIA a cobrança e lança timeout (1ª tentativa) — o poll
         * {@code getChargeByCode} acha; o retry idempotente devolve a cobrança. */
        TIMEOUT_AFTER_CREATE
    }

    private final PspProperties pspProperties;
    private final String providerName;

    /** Simulação dinâmica (volatile — setada pelo endpoint dev de teste). */
    private volatile Simulation simulation = Simulation.NORMAL;

    /** Cobranças já criadas por transactionId (idempotência do provedor real). */
    private final Map<UUID, CreateChargeResult> createdByTransaction = new ConcurrentHashMap<>();

    /** Transações que já sofreram o timeout simulado (1ª tentativa por transação). */
    private final Set<UUID> timedOutTransactions = ConcurrentHashMap.newKeySet();

    /** Bean "mock" (padrão — @Autowired resolve os 2 construtores). */
    @org.springframework.beans.factory.annotation.Autowired
    public MockPixProviderClient(PspProperties pspProperties) {
        this(pspProperties, "mock");
    }

    public MockPixProviderClient(PspProperties pspProperties, String providerName) {
        this.pspProperties = pspProperties;
        this.providerName = providerName;
    }

    /** Nome de roteamento do mock (ex.: "mock", "suitpay"). */
    public String providerName() {
        return providerName;
    }

    /** Modo de simulação atual (dev/tests). */
    public Simulation simulation() {
        return simulation;
    }

    /** Define o modo de simulação (Spec 91 — endpoint dev de controle). */
    public void setSimulation(Simulation simulation) {
        this.simulation = simulation;
    }

    @Override
    public CreateChargeResult createCharge(CreateChargeCommand command) {
        Simulation sim = simulation;
        // Timeout simulado por config (Spec 28, dev/tests): apenas o provedor "mock"
        // sofre a simulação estática; o 2º mock (ex.: "suitpay") é sempre limpo.
        if (sim == Simulation.NORMAL && "mock".equals(providerName) && pspProperties.isMockTimeout()
                && timedOutTransactions.add(command.transactionId())) {
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Mock PSP: timeout simulado (foxcash.psp.mock-timeout=true) para transacao="
                            + command.transactionId(), null);
        }
        // Simulação dinâmica (Spec 91 — E2E): 5xx incerto / timeout sem criar.
        if (sim == Simulation.TIMEOUT && timedOutTransactions.add(command.transactionId())) {
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Mock PSP: timeout simulado (simulacao dinamica) para transacao="
                            + command.transactionId(), null);
        }
        if (sim == Simulation.FAILURE) {
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Mock PSP: 5xx simulado (simulacao dinamica) para transacao="
                            + command.transactionId(), null);
        }

        // Idempotência do provedor real: re-envio com o mesmo code (transactionId)
        // devolve a MESMA cobrança — nunca duplica no PSP.
        CreateChargeResult cached = createdByTransaction.get(command.transactionId());
        if (cached != null) {
            log.info("Mock PSP: cobranca ja existente para transacao={} (idempotencia), externalReference={}",
                    command.transactionId(), cached.externalReference());
            return cached;
        }

        // Simula o PSP: referencia no formato E<32 digitos hexadecimais>
        String externalReference = "E" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        // BR Code (Pix Copia e Cola) fake, estruturalmente plausivel
        String pixCopyPaste = buildBrCode(externalReference, command.amount().amount());

        // QR Code em base64 (imagem fake — em producao seria gerado pelo PSP)
        String pixQrCode = Base64.getEncoder()
                .encodeToString(pixCopyPaste.getBytes(StandardCharsets.UTF_8));

        CreateChargeResult result = new CreateChargeResult(externalReference, pixQrCode, pixCopyPaste);
        createdByTransaction.put(command.transactionId(), result);

        // Timeout APÓS criar (Spec 91 — poll anti-dupla-cobrança do E2E): a cobrança
        // existe no "PSP" (o getChargeByCode acha) mas a resposta foi incerta. O
        // retry idempotente (mesmo transactionId) devolve a cobrança cacheada.
        if (sim == Simulation.TIMEOUT_AFTER_CREATE && timedOutTransactions.add(command.transactionId())) {
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Mock PSP: timeout apos criar cobranca (simulacao dinamica) para transacao="
                            + command.transactionId(), null);
        }

        log.info("Mock PSP: cobranca criada para transacao={}, externalReference={}",
                command.transactionId(), externalReference);

        return result;
    }

    @Override
    public GetChargeResult getChargeByCode(String code) {
        try {
            CreateChargeResult cached = createdByTransaction.get(UUID.fromString(code));
            if (cached == null) {
                return new GetChargeResult(false, null, null, null);
            }
            // Estado bruto do "PSP": pending enquanto não recebeu webhook PAID.
            return new GetChargeResult(true, cached.pixQrCode(), cached.pixCopyPaste(), "pending");
        } catch (IllegalArgumentException e) {
            // code não é UUID conhecido → cobrança inexistente no mock.
            return new GetChargeResult(false, null, null, null);
        }
    }

    private String buildBrCode(String externalReference, BigDecimal amount) {
        DecimalFormat fmt = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
        String txid = externalReference.substring(1, Math.min(27, externalReference.length()));
        // Estrutura: Payload + Merchant Account (BCB.PIX + endToEndId) + valor +
        // nome/loja + cidade + txid. CRC (6304) fake para desenvolvimento.
        return "00020126580014BR.GOV.BCB.PIX0136" + externalReference
                + "52040000530398654" + fmt.format(amount)
                + "5802BR5920FOXCASH PIX COBRANCA6009SAO PAULO62070503" + txid
                + "6304FFFF";
    }
}
