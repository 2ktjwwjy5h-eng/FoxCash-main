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
 * Testes do {@link AsaasPixProviderClient} contra um MockWebServer que reproduz
 * o contrato da API v3 do Asaas (POST /v3/pix/charges, GET
 * /v3/pix/charges?externalReference=, auth via access_token).
 *
 * <p>Herda {@link PixProviderContractTest} — o contrato da porta é verificado
 * automaticamente (create + consulta por code + falhas).
 */
class AsaasPixProviderClientTest extends PixProviderContractTest {

    private static final String TOKEN = "asaas_test_token";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Code (= transactionId) conhecido no "PSP" — o command() usa este UUID. */
    private static final String KNOWN_CODE = "8fdc7640-f6fa-47d0-a49f-4a722b125a74";

    private enum Mode { NORMAL, FAIL, TIMEOUT }

    private MockWebServer server;
    private AsaasPixProviderClient client;
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
                if ("GET".equals(request.getMethod()) && path.startsWith("/v3/pix/charges")) {
                    if (mode == Mode.TIMEOUT) {
                        return json(503, "{\"errors\":[{\"description\":\"unavailable\"}]}");
                    }
                    String externalReference = externalReferenceFrom(path);
                    if (KNOWN_CODE.equals(externalReference)) {
                        return json(200, "{\"data\":[" + chargeJson() + "]}");
                    }
                    return json(200, "{\"data\":[]}");
                }
                if ("POST".equals(request.getMethod()) && path.startsWith("/v3/pix/charges")) {
                    return switch (mode) {
                        case FAIL -> json(422, "{\"errors\":[{\"code\":\"invalid_value\",\"description\":\"valor invalido\"}]}");
                        case TIMEOUT -> json(503, "{\"errors\":[{\"description\":\"unavailable\"}]}");
                        case NORMAL -> json(200, chargeJson());
                    };
                }
                return json(404, "{}");
            }
        });

        PspProperties properties = new PspProperties();
        properties.setProvider("asaas");
        properties.getWebhook().setSecret("webhook-secret");
        properties.getAsaas().setSecretKey(TOKEN);
        properties.getAsaas().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        client = new AsaasPixProviderClient(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static String externalReferenceFrom(String path) {
        int q = path.indexOf("externalReference=");
        if (q < 0) {
            return "";
        }
        return path.substring(q + "externalReference=".length());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String chargeJson() {
        return """
                {"id":"pay_asaas_001","status":"PENDING",
                 "pixQrCode":"aWFtYWdlLXBuZw==","encodedImage":"aWFtYWdlLXBuZw==",
                 "payload":"000201ASAAASBRCODE"}
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
                null);
    }

    @Test
    void createsChargeWithAuthAndExternalReference() throws Exception {
        PixProviderClient.CreateChargeResult result = client.createCharge(command());

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("access_token")).isEqualTo(TOKEN);
        assertThat(request.getPath()).isEqualTo("/v3/pix/charges");

        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("billingType").asText()).isEqualTo("PIX");
        assertThat(body.get("value").decimalValue()).isEqualByComparingTo("10.50");
        assertThat(body.get("externalReference").asText()).isEqualTo(KNOWN_CODE);

        assertThat(result.externalReference()).isEqualTo("pay_asaas_001");
        assertThat(result.pixQrCode()).isEqualTo("aWFtYWdlLXBuZw==");
        assertThat(result.pixCopyPaste()).isEqualTo("000201ASAAASBRCODE");
    }

    @Test
    void throwsOnApiError() throws Exception {
        mode = Mode.FAIL;

        assertThatThrownBy(() -> client.createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderException.class)
                .hasMessageContaining("Asaas: falha ao criar cobranca Pix");
    }

    @Test
    void throwsOnServerErrorAsIncertain() throws Exception {
        mode = Mode.TIMEOUT;

        assertThatThrownBy(() -> client.createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class)
                .hasMessageContaining("5xx");
    }

    @Test
    void getChargeByCodeBuscaPorExternalReference() throws Exception {
        PixProviderClient.GetChargeResult found = client.getChargeByCode(KNOWN_CODE);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v3/pix/charges?externalReference=" + KNOWN_CODE);

        assertThat(found.found()).isTrue();
        assertThat(found.pixCopyPaste()).isEqualTo("000201ASAAASBRCODE");
    }

    @Test
    void getChargeByCodeCom503LancaTimeout() throws Exception {
        mode = Mode.TIMEOUT;

        assertThatThrownBy(() -> client.getChargeByCode(KNOWN_CODE))
                .isInstanceOf(PixProviderClient.PixProviderTimeoutException.class)
                .hasMessageContaining("consultar");
    }
}
