package com.foxcash.infrastructure.outbound.pix;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.util.List;

/**
 * Adapter real do provedor Pix **Asaas** (API v3) — Spec 91.
 *
 * <p>{@code POST /v3/pix/charges} cria a cobrança Pix (auth via header
 * {@code access_token}); o {@code code} (= transactionId) vai no
 * {@code externalReference} do Asaas (idempotência no provedor e busca no poll
 * anti-dupla-cobrança: {@code GET /v3/pix/charges?externalReference={code}}).
 *
 * <p><b>Bean sempre (Spec 91):</b> registrado SEMPRE — o {@code PixProviderRouter}
 * decide a ordem; o fail-fast de credencial continua no
 * {@code PspProperties.validate()} (secret-key obrigatória quando o Asaas for
 * usado). Timeouts 5s/10s (padrão Spec 26/M2).
 *
 * <p><b>Erros (Spec 91):</b> timeout de rede e **5xx** → {@code PixProviderTimeoutException}
 * (resposta INCERTA — o router faz o poll anti-dupla-cobrança); **4xx**
 * (rejeição de negócio) → {@code PixProviderException} (definitivo — fallback
 * imediato do router).
 */
@Component
public class AsaasPixProviderClient implements PixProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AsaasPixProviderClient.class);

    private final RestClient restClient;

    public AsaasPixProviderClient(PspProperties properties) {
        PspProperties.Asaas asaas = properties.getAsaas();

        // Timeouts 5s/10s (padrão do PagarMePixProviderClient, Spec 11/26).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .baseUrl(asaas.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("access_token", asaas.getSecretKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public CreateChargeResult createCharge(CreateChargeCommand command) {
        // code (= transactionId) no externalReference do Asaas — idempotência no
        // provedor e chave da busca do poll anti-dupla-cobrança (Spec 91).
        CreatePixChargeRequest body = new CreatePixChargeRequest(
                "PIX",
                command.amount().amount(),
                command.transactionId().toString(),
                command.description());

        log.info("Asaas: criando cobranca Pix para transacao={}, amount={}",
                command.transactionId(), command.amount());

        try {
            CreatePixChargeResponse response = restClient.post()
                    .uri("/v3/pix/charges")
                    .body(body)
                    .retrieve()
                    .body(CreatePixChargeResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new IllegalStateException(
                        "Asaas: resposta invalida (sem id) para transacao=" + command.transactionId());
            }
            String pixQrCode = response.pixQrCode() != null ? response.pixQrCode() : response.encodedImage();
            if (response.payload() == null || response.payload().isBlank()) {
                throw new IllegalStateException(
                        "Asaas: cobranca sem payload (copia-e-cola) (charge_id=" + response.id() + ")");
            }

            log.info("Asaas: cobranca criada (id={}, externalReference={})",
                    response.id(), command.transactionId());

            // externalReference = id da cobrança no Asaas — usado pelo webhook
            // (o Asaas envia o id/status no callback).
            return new CreateChargeResult(response.id(), pixQrCode, response.payload());
        } catch (ResourceAccessException e) {
            // Timeout de connect/read (Spec 28): resposta INCERTA — a cobrança
            // PODE ter sido criada (retry com o mesmo code é idempotente).
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Asaas: timeout ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        } catch (HttpServerErrorException e) {
            // 5xx (Spec 91): resposta INCERTA — o router faz o poll anti-dupla-cobrança.
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Asaas: 5xx ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        } catch (RestClientException e) {
            // 4xx real do PSP (rejeição de negócio, Spec 91): definitivo — fallback imediato.
            throw new PixProviderClient.PixProviderException(
                    "Asaas: falha ao criar cobranca Pix (transacao=" + command.transactionId() + ")", e);
        }
    }

    @Override
    public GetChargeResult getChargeByCode(String code) {
        log.info("Asaas: consultando cobranca pelo externalReference={}", code);
        try {
            // O code (= transactionId) vai no externalReference do Asaas (D2.1).
            ChargeListResponse response = restClient.get()
                    .uri("/v3/pix/charges?externalReference={code}", code)
                    .retrieve()
                    .body(ChargeListResponse.class);
            if (response == null || response.data() == null || response.data().isEmpty()) {
                return new GetChargeResult(false, null, null, null);
            }
            ChargeData charge = response.data().get(0);
            if ("DELETED".equalsIgnoreCase(charge.status())) {
                return new GetChargeResult(false, null, null, null);
            }
            String pixQrCode = charge.pixQrCode() != null ? charge.pixQrCode() : charge.encodedImage();
            return new GetChargeResult(true, pixQrCode, charge.payload(), charge.status());
        } catch (ResourceAccessException e) {
            // Timeout da consulta: incerto — o router trata como "não confirmou".
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Asaas: timeout ao consultar cobranca (code=" + code + ")", e);
        } catch (RestClientException e) {
            // 5xx (ou outro erro) da consulta: incerto — mesmo tratamento do timeout.
            throw new PixProviderClient.PixProviderTimeoutException(
                    "Asaas: falha ao consultar cobranca (code=" + code + ")", e);
        }
    }

    /** Valor em {@code BigDecimal} — o Asaas aceita o valor direto (sem centavos). */

    /** POST /v3/pix/charges — request (billingType=PIX). */
    record CreatePixChargeRequest(
            @JsonProperty("billingType") String billingType,
            BigDecimal value,
            @JsonProperty("externalReference") String externalReference,
            String description) {
    }

    /** POST /v3/pix/charges response — campos usados. */
    record CreatePixChargeResponse(
            String id,
            String status,
            @JsonProperty("pixQrCode") String pixQrCode,
            @JsonProperty("encodedImage") String encodedImage,
            String payload) {
    }

    /** GET /v3/pix/charges?externalReference= response — lista {@code data[]}. */
    record ChargeListResponse(List<ChargeData> data) {
    }

    record ChargeData(String id, String status,
                      @JsonProperty("pixQrCode") String pixQrCode,
                      @JsonProperty("encodedImage") String encodedImage,
                      String payload) {
    }
}
