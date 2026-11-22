package com.foxcash.infrastructure.outbound.pix;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Adapter real de PIX de saída **Pagar.me** (API v5) — Spec 47.
 *
 * <p>Implementa {@link PayoutProviderClient#createPayout} via
 * {@code POST {base-url}/transfers} com autenticação HTTP Basic (secret key),
 * {@code code} = id do saque (idempotente no adquirente — re-entrega com o
 * mesmo id não duplica a transferência, padrão Spec 28) e destino como chave
 * Pix ({@code pix.pix_key}/{@code pix.pix_key_type}). {@code getPayout}
 * consulta {@code GET {base-url}/transfers/{id}} e mapeia o vocabulário do
 * Pagar.me para o canônico da porta (Spec 38): {@code processed} → PAID,
 * {@code failed}/{@code canceled} → FAILED, demais ({@code pending},
 * {@code in_process}...) → PENDING (a reconciliação NÃO liquida PENDING).
 *
 * <p><b>Tipo da chave Pix (pix_key_type):</b> a porta não carrega o tipo do
 * destino (zero mudança no núcleo — critério da Spec 47); o adapter infere do
 * formato canônico validado pela Spec 31: CPF (11 dígitos) → {@code cpf},
 * CNPJ (14 dígitos) → {@code cnpj}, telefone ({@code +55...}) → {@code phone},
 * chave aleatória (UUID) → {@code evp}. Destino {@code BANK_ACCOUNT} (TED)
 * não é suportado neste adapter (exigiria {@code document_number} do titular,
 * que o domínio não captura) — lança {@link PayoutProviderException}.
 *
 * <p>Timeouts 5s/10s (padrão do {@link PagarMePixProviderClient}, Spec 11):
 * {@code ResourceAccessException} → {@link PayoutProviderClient.PayoutProviderTimeoutException}
 * (resposta incerta no provedor); {@code RestClientException} →
 * {@link PayoutProviderClient.PayoutProviderException}.
 *
 * <p>Registrado SEMPRE como bean (Spec 92 — o {@code PayoutProviderRouter} roteia
 * por prioridade; o fail-fast de credencial continua no
 * {@code PspProperties.validate()}: payout-pagarme.secret-key obrigatória
 * quando o Pagar.me for usado).
 */
@Component
public class PagarMePayoutProviderClient implements PayoutProviderClient {

    private static final Logger log = LoggerFactory.getLogger(PagarMePayoutProviderClient.class);

    private final RestClient restClient;

    public PagarMePayoutProviderClient(PspProperties properties) {
        PspProperties.PayoutPsp pagarme = properties.getPayoutPagarme();

        String credentials = Base64.getEncoder().encodeToString(
                (pagarme.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));

        // Timeouts (padrão Spec 11): Connect 5s / Read 10s — uma lentidão do
        // PSP não segura a thread indefinidamente.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .baseUrl(pagarme.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public PayoutResult createPayout(PayoutCommand command) {
        PayoutDestination destination = command.destination();
        if (destination.pixKey() == null || destination.pixKey().isBlank()) {
            throw new PayoutProviderException(
                    "Pagar.me: destino sem chave Pix (BANK_ACCOUNT/TED nao suportado neste adapter — cadastre uma chave Pix)");
        }
        String pixKeyType = inferPixKeyType(destination.pixKey());
        TransferRequest body = new TransferRequest(
                toCents(command.amount()),
                command.withdrawalId().toString(),
                new PixRequest(destination.pixKey(), pixKeyType));

        log.info("Pagar.me: solicitando PIX de saida para saque={}, amount={}, pix_key_type={}",
                command.withdrawalId(), command.amount(), pixKeyType);

        try {
            TransferResponse response = restClient.post()
                    .uri("/transfers")
                    .body(body)
                    .retrieve()
                    .body(TransferResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new IllegalStateException(
                        "Pagar.me: resposta invalida (sem id) para saque=" + command.withdrawalId());
            }

            log.info("Pagar.me: PIX de saida aceito (transfer_id={}, externalReference={})",
                    response.id(), command.withdrawalId());
            return new PayoutResult(response.id());
        } catch (ResourceAccessException e) {
            // Timeout de connect/read: a resposta é INCERTA — o payout PODE ter
            // sido aceito no provedor. O fluxo de saque estorna e o retry com o
            // MESMO code (= withdrawal.id) é idempotente no Pagar.me (B-04).
            throw new PayoutProviderClient.PayoutProviderTimeoutException(
                    "Pagar.me: timeout ao solicitar PIX de saida (saque=" + command.withdrawalId() + ")", e);
        } catch (RestClientException e) {
            // Erro real do PSP (4xx/5xx) — o code de idempotência já produziu resultado.
            throw new PayoutProviderClient.PayoutProviderException(
                    "Pagar.me: falha ao solicitar PIX de saida (saque=" + command.withdrawalId() + ")", e);
        }
    }

    @Override
    public PayoutStatus getPayout(String externalReference) {
        try {
            TransferResponse response = restClient.get()
                    .uri("/transfers/{id}", externalReference)
                    .retrieve()
                    .body(TransferResponse.class);
            if (response == null || response.status() == null) {
                throw new IllegalStateException(
                        "Pagar.me: resposta invalida (sem status) para transfer=" + externalReference);
            }
            // Vocabulário do Pagar.me → canônico (Spec 38): PENDING não liquida.
            return switch (response.status()) {
                case "processed" -> PayoutStatus.PAID;
                case "failed", "canceled" -> PayoutStatus.FAILED;
                default -> PayoutStatus.PENDING; // pending, in_process, etc.
            };
        } catch (ResourceAccessException e) {
            throw new PayoutProviderClient.PayoutProviderTimeoutException(
                    "Pagar.me: timeout ao consultar transfer=" + externalReference, e);
        } catch (RestClientException e) {
            throw new PayoutProviderClient.PayoutProviderException(
                    "Pagar.me: falha ao consultar transfer=" + externalReference, e);
        }
    }

    /**
     * Infere o {@code pix_key_type} do formato canônico da chave (Spec 31
     * valida os formatos no cadastro do destino): CPF 11 dígitos, CNPJ 14
     * dígitos, telefone BR {@code +55...}, chave aleatória (UUID). A chave pode
     * vir com pontuação (ex.: {@code 529.982.247-25}) — normaliza dígitos.
     */
    static String inferPixKeyType(String pixKey) {
        String digits = pixKey.replaceAll("\\D", "");
        if (digits.length() == 11) {
            return "cpf";
        }
        if (digits.length() == 14) {
            return "cnpj";
        }
        if (pixKey.startsWith("+")) {
            return "phone";
        }
        if (pixKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return "evp";
        }
        throw new PayoutProviderClient.PayoutProviderException(
                "Pagar.me: chave Pix com tipo desconhecido: " + mask(pixKey));
    }

    /** Máscara para logs/mensagens — nunca a chave em claro. */
    private static String mask(String pixKey) {
        if (pixKey.length() <= 4) {
            return "***";
        }
        return pixKey.substring(0, 2) + "***" + pixKey.substring(pixKey.length() - 2);
    }

    /** Centavos em {@code long} — mesmo padrão do {@link PagarMePixProviderClient} (auditoria B1). */
    private static long toCents(Money money) {
        return money.amount().movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** POST /transfers — request (Pagar.me v5). */
    record TransferRequest(
            long amount,
            String code,
            @JsonProperty("pix") PixRequest pix) {
    }

    record PixRequest(
            @JsonProperty("pix_key") String pixKey,
            @JsonProperty("pix_key_type") String pixKeyType) {
    }

    /** POST/GET /transfers response — apenas campos usados (id, status). */
    record TransferResponse(
            String id,
            String status) {
    }
}
