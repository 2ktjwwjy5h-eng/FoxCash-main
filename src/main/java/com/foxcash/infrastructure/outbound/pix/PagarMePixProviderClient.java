package com.foxcash.infrastructure.outbound.pix;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Adapter real do provedor Pix **Pagar.me** (API v5).
 *
 * <p>Implementa {@link PixProviderClient#createCharge} via
 * {@code POST {base-url}/charges} com autenticacao HTTP Basic (secret key).
 * Contrato verificado no SDK oficial arquivado {@code pagarme/pagarme-core-api-java}
 * (APIMATIC v5.7.5): {@code CreateChargeRequest} (code, amount em centavos,
 * payment.payment_method="pix", payment.pix.expires_in) e
 * {@code GetChargeResponse.last_transaction} ({@code qr_code},
 * {@code qr_code_url}, {@code id}).
 *
 * <p><b>Bean sempre (Spec 91):</b> registrado SEMPRE — o {@code PixProviderRouter}
 * decide a ordem de prioridade; o fail-fast de credencial continua no
 * {@code PspProperties.validate()} (secret-key obrigatória quando o Pagar.me for
 * usado). {@code getChargeByCode} consulta por {@code ?code=} (busca por código,
 * v5) — o id da cobrança é desconhecido no timeout.
 *
 * <p><b>Erros (Spec 91):</b> timeout de rede e **5xx** → {@code PixProviderTimeoutException}
 * (resposta INCERTA — a cobrança pode ter sido criada; o router faz o poll
 * anti-dupla-cobrança); **4xx** (rejeição de negócio) → {@code PixProviderException}
 * (definitivo — fallback imediato do router).
 */
@Component
public class PagarMePixProviderClient implements PixProviderClient {

    private static final Logger log = LoggerFactory.getLogger(PagarMePixProviderClient.class);

    private final RestClient restClient;
    private final int expiresInSeconds;

    public PagarMePixProviderClient(PspProperties properties) {
        PspProperties.Pagarme pagarme = properties.getPagarme();
        this.expiresInSeconds = pagarme.getExpiresInSeconds();

        String credentials = Base64.getEncoder().encodeToString(
                (pagarme.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));

        // Timeouts (QA 2026-08-04): sem eles, uma lentidão do PSP segura a
        // thread indefinidamente. Connect 5s / Read 10s.
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
    public CreateChargeResult createCharge(CreateChargeCommand command) {
        // Expiração: o valor enviado pelo cliente (portal/bot) prevalece; sem ele,
        // usa o default configurado (expires-in-seconds). Antes o parâmetro da API
        // era aceito mas ignorado (QA 2026-08-04).
        int expiresIn = command.expirationSeconds() != null
                ? command.expirationSeconds() : expiresInSeconds;
        CreateChargeRequest body = new CreateChargeRequest(
                command.transactionId().toString(),
                toCents(command.amount()),
                new PaymentRequest(
                        "pix",
                        toCents(command.amount()),
                        new PixPaymentRequest(expiresIn)));

        log.info("Pagar.me: criando cobranca Pix para transacao={}, amount={}",
                command.transactionId(), command.amount());

        try {
            CreateChargeResponse response = restClient.post()
                    .uri("/charges")
                    .body(body)
                    .retrieve()
                    .body(CreateChargeResponse.class);

            if (response == null || response.id() == null || response.lastTransaction() == null) {
                throw new IllegalStateException(
                        "Pagar.me: resposta invalida (sem id ou last_transaction) para transacao="
                                + command.transactionId());
            }

            String pixCopyPaste = response.lastTransaction().qrCode();
            String pixQrCode = response.lastTransaction().qrCodeUrl();
            if (pixCopyPaste == null || pixQrCode == null) {
                throw new IllegalStateException(
                        "Pagar.me: cobranca sem qr_code/qr_code_url (charge_id=" + response.id() + ")");
            }

            log.info("Pagar.me: cobranca criada (charge_id={}, externalReference={})",
                    response.id(), command.transactionId());

            // externalReference = id da charge no Pagar.me (ch_...) — usado pelo
            // webhook charge.paid (data.id) para localizar a transacao.
            return new CreateChargeResult(response.id(), pixQrCode, pixCopyPaste);
        } catch (ResourceAccessException e) {
            // Timeout de connect/read (Spec 28): a resposta é INCERTA — a cobrança
            // PODE ter sido criada no provedor. O fluxo mantém a PENDING e o retry
            // com o mesmo code (= transactionId) é idempotente no Pagar.me.
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Pagar.me: timeout ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        } catch (HttpServerErrorException e) {
            // 5xx (Spec 91): resposta INCERTA — o PSP pode ter criado a cobrança.
            // O router faz o poll anti-dupla-cobrança (getChargeByCode) antes do fallback.
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Pagar.me: 5xx ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        } catch (RestClientException e) {
            // 4xx real do PSP (rejeição de negócio, Spec 91): definitivo — a chave
            // de idempotência já produziu resultado e o fallback é imediato (sem poll).
            throw new PixProviderClient.PixProviderException(
                    "Pagar.me: falha ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        }
    }

    @Override
    public GetChargeResult getChargeByCode(String code) {
        log.info("Pagar.me: consultando cobranca pelo code={}", code);
        try {
            // Busca por código (v5): o id da charge é desconhecido no timeout — o
            // único identificador que conhecemos é o code (= transactionId) enviado.
            ChargeListResponse response = restClient.get()
                    .uri("/charges?code={code}", code)
                    .retrieve()
                    .body(ChargeListResponse.class);
            if (response == null || response.data() == null || response.data().isEmpty()) {
                return new GetChargeResult(false, null, null, null);
            }
            ChargeData charge = response.data().get(0);
            if ("deleted".equalsIgnoreCase(charge.status())) {
                return new GetChargeResult(false, null, null, null);
            }
            LastTransaction lt = charge.lastTransaction();
            return new GetChargeResult(true,
                    lt != null ? lt.qrCodeUrl() : null,
                    lt != null ? lt.qrCode() : null,
                    charge.status());
        } catch (ResourceAccessException e) {
            // Timeout da consulta: resposta incerta — o router trata como
            // "não confirmou que não criou" (não arrisca dupla cobrança).
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Pagar.me: timeout ao consultar cobranca (code=" + code + ")", e);
        } catch (RestClientException e) {
            // 5xx (ou outro erro) da consulta: incerto — mesmo tratamento do timeout.
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Pagar.me: falha ao consultar cobranca (code=" + code + ")", e);
        }
    }

    /** Centavos em {@code long} — auditoria 2026-08-05 (B1): {@code int} estourava
     * silenciosamente acima de R$ 21.474.836,47 (2^31-1 centavos). */
    private static long toCents(Money money) {
        return money.amount().movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** POST /charges — request (CreateChargeRequest). */
    record CreateChargeRequest(
            String code,
            long amount,
            @JsonProperty("payment") PaymentRequest payment) {
    }

    record PaymentRequest(
            @JsonProperty("payment_method") String paymentMethod,
            long amount,
            @JsonProperty("pix") PixPaymentRequest pix) {
    }

    record PixPaymentRequest(@JsonProperty("expires_in") int expiresIn) {
    }

    /** POST /charges response (GetChargeResponse) — apenas campos usados. */
    record CreateChargeResponse(
            String id,
            @JsonProperty("last_transaction") LastTransaction lastTransaction) {
    }

    record LastTransaction(
            @JsonProperty("qr_code") String qrCode,
            @JsonProperty("qr_code_url") String qrCodeUrl,
            String id) {
    }

    /** GET /charges?code= response — busca por código (v5): {@code data[]}. */
    record ChargeListResponse(List<ChargeData> data) {
    }

    record ChargeData(String id, String status,
                      @JsonProperty("last_transaction") LastTransaction lastTransaction) {
    }
}
