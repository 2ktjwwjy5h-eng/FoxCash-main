package com.foxcash.infrastructure.outbound.pix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link PagarMePixProviderClient} contra um MockWebServer que
 * reproduz o contrato da API v5 do Pagar.me (POST /charges, GET /charges?code=,
 * auth Basic, GetChargeResponse.last_transaction).
 *
 * <p>Herda {@link PixProviderContractTest} — o contrato da porta é verificado
 * automaticamente (create + consulta por code + falhas). O dispatcher simula os
 * modos NORMAL / 422 (erro definitivo) / 503 (resposta incerta) / NO_QR.
 */
class PagarMePixProviderClientTest extends PixProviderContractTest {

    private static final String SECRET_KEY = "sk_test_123456";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Code (= transactionId) conhecido no "PSP" — o command() usa este UUID. */
    private static final String KNOWN_CODE = "8fdc7640-f6fa-47d0-a49f-4a722b125a74";

    private enum Mode { NORMAL, FAIL, TIMEOUT, NO_QR }

    private MockWebServer server;
    private PagarMePixProviderClient client;
    private volatile Mode mode = Mode.NORMAL;

    @BeforeEach
    void setUp() throws Exception {
        mode = Mode.NORMAL;
        server = new MockWebServer();
        server.start();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                if ("GET".equals(request.getMethod()) && path.startsWith("/charges")) {
                    // GET /charges?code=... — busca por código (Spec 91).
                    // 5xx da consulta = resposta incerta → PixProviderTimeoutException.
                    if (mode == Mode.TIMEOUT) {
                        return json(503, "{\"message\":\"unavailable\"}");
                    }
                    String code = codeFrom(path);
                    if (KNOWN_CODE.equals(code)) {
                        // Resposta do GET é uma LISTA ({"data":[...]}) — v5.
                        return json(200, "{\"data\":[" + chargeJson() + "]}");
                    }
                    return json(200, "{\"data\":[]}");
                }
                if ("POST".equals(request.getMethod()) && path.startsWith("/charges")) {
                    return switch (mode) {
                        case FAIL -> json(422, "{\"message\":\"invalid pix key\"}");
                        case TIMEOUT -> json(503, "{\"message\":\"unavailable\"}");
                        case NO_QR -> json(200,
                                "{\"id\":\"ch_1\",\"last_transaction\":{\"id\":\"t1\",\"qr_code\":null,\"qr_code_url\":null}}");
                        case NORMAL -> json(200, chargeJson());
                    };
                }
                return json(404, "{}");
            }
        });

        PspProperties properties = new PspProperties();
        properties.setProvider("pagarme");
        properties.getWebhook().setSecret("webhook-secret");
        properties.getPagarme().setSecretKey(SECRET_KEY);
        properties.getPagarme().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.getPagarme().setExpiresInSeconds(1800);
        client = new PagarMePixProviderClient(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static String codeFrom(String path) {
        int q = path.indexOf("code=");
        if (q < 0) {
            return "";
        }
        return path.substring(q + 5);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String chargeJson() {
        return """
                {"id":"ch_test_001","status":"pending",
                 "last_transaction":{"id":"txn_001","qr_code":"000201BRCODE",
                 "qr_code_url":"https://pix.pagar.me/qr.png"}}
                """;
    }

    @Override
    protected PixProviderClient client() {
        mode = Mode.NORMAL;
        return client;
    }

    @Override
    protected PixProviderClient failingClient() {
        mode = Mode.FAIL;
        return client;
    }

    @Override
    protected PixProviderClient timeoutClient() {
        mode = Mode.TIMEOUT;
        return client;
    }

    @Override
    protected PixProviderClient.CreateChargeCommand command() {
        return new PixProviderClient.CreateChargeCommand(
                UUID.fromString(KNOWN_CODE),
                UUID.fromString("1fdc7640-f6fa-47d0-a49f-4a722b125a74"),
                Money.of(new BigDecimal("10.50")),
                null,
                "Cobranca Pix",
                null); // sem expiração explícita → usa o default configurado
    }

    @Test
    void createsChargeWithCorrectRequestAndAuth() throws Exception {
        PixProviderClient.CreateChargeResult result = client.createCharge(command());

        // Auth Basic: base64(secretKey:)
        RecordedRequest request = server.takeRequest();
        String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(request.getHeader("Authorization")).isEqualTo(expectedAuth);
        assertThat(request.getPath()).isEqualTo("/charges");

        // Corpo: code, amount em centavos, payment_method=pix, expires_in
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("code").asText()).isEqualTo(KNOWN_CODE);
        assertThat(body.get("amount").asInt()).isEqualTo(1050);
        assertThat(body.get("payment").get("payment_method").asText()).isEqualTo("pix");
        assertThat(body.get("payment").get("pix").get("expires_in").asInt()).isEqualTo(1800);

        // Mapeamento da resposta
        assertThat(result.externalReference()).isEqualTo("ch_test_001");
        assertThat(result.pixQrCode()).isEqualTo("https://pix.pagar.me/qr.png");
        assertThat(result.pixCopyPaste()).isEqualTo("000201BRCODE");
    }

    @Test
    void mapsAmountInCents() throws Exception {
        client.createCharge(command());

        RecordedRequest request = server.takeRequest();
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        // R$ 10,50 -> 1050 centavos
        assertThat(body.get("amount").asInt()).isEqualTo(1050);
        assertThat(body.get("payment").get("amount").asInt()).isEqualTo(1050);
    }

    @Test
    void throwsOnApiError() throws Exception {
        mode = Mode.FAIL;

        assertThatThrownBy(() -> client.createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderException.class)
                .hasMessageContaining("Pagar.me: falha ao criar cobranca Pix");
    }

    @Test
    void throwsOnServerErrorAsIncertain() throws Exception {
        mode = Mode.TIMEOUT;

        // 5xx (Spec 91): resposta INCERTA → PixProviderTimeoutException (o router
        // faz o poll anti-dupla-cobrança antes do fallback).
        assertThatThrownBy(() -> client.createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class)
                .hasMessageContaining("5xx");
    }

    @Test
    void throwsWhenResponseMissingQrCode() throws Exception {
        mode = Mode.NO_QR;

        assertThatThrownBy(() -> client.createCharge(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem qr_code");
    }

    @Test
    void clientExpirationOverridesConfigDefault() throws Exception {
        // Expiração explícita no comando (portal/bot) prevalece sobre o default 1800.
        client.createCharge(new PixProviderClient.CreateChargeCommand(
                UUID.fromString(KNOWN_CODE),
                UUID.fromString("1fdc7640-f6fa-47d0-a49f-4a722b125a74"),
                Money.of(new BigDecimal("10.50")),
                null,
                "Cobranca",
                7200));

        RecordedRequest request = server.takeRequest();
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("payment").get("pix").get("expires_in").asInt()).isEqualTo(7200);
    }

    @Test
    void getChargeByCodeBuscaPorCodeEComQr() throws Exception {
        PixProviderClient.GetChargeResult found = client.getChargeByCode(KNOWN_CODE);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/charges?code=" + KNOWN_CODE);

        assertThat(found.found()).isTrue();
        assertThat(found.pixCopyPaste()).isEqualTo("000201BRCODE");
        assertThat(found.pixQrCode()).isEqualTo("https://pix.pagar.me/qr.png");
    }

    @Test
    void getChargeByCodeCom503LancaTimeout() throws Exception {
        mode = Mode.TIMEOUT;

        assertThatThrownBy(() -> client.getChargeByCode(KNOWN_CODE))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class)
                .hasMessageContaining("consultar");
    }
}
