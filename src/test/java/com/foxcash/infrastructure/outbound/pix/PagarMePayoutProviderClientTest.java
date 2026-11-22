package com.foxcash.infrastructure.outbound.pix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PayoutProviderClient;
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
 * Testes do {@link PagarMePayoutProviderClient} (Spec 47): herda o
 * {@link PayoutProviderContractTest} (contrato da porta — invariantes
 * verificados automaticamente) e cobre o contrato v5 da API de transfers do
 * Pagar.me contra um MockWebServer: auth Basic, body (code/amount/pix),
 * mapeamento de estados e erros tipados.
 */
class PagarMePayoutProviderClientTest extends PayoutProviderContractTest {

    private static final String SECRET_KEY = "sk_test_123456";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWebServer server;
    private MockWebServer failingServer;
    private PagarMePayoutProviderClient client;
    private PagarMePayoutProviderClient failingClient;

    @BeforeEach
    void setUp() throws Exception {
        // Dispatcher do contrato: POST /transfers → 200 com id; GET /transfers/{id}
        // → 200 com status (PENDING) — cobre os 3 invariantes do harness abstrato.
        server = new MockWebServer();
        server.start();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod()) && "/transfers".equals(request.getPath())) {
                    return jsonResponse("{\"id\":\"tr_test_001\",\"status\":\"pending\"}");
                }
                if ("GET".equals(request.getMethod()) && request.getPath() != null
                        && request.getPath().startsWith("/transfers/")) {
                    return jsonResponse("{\"id\":\"tr_test_001\",\"status\":\"pending\"}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        client = new PagarMePayoutProviderClient(properties(server));

        // failingClient: POST /transfers → 500 (falha síncrona → RuntimeException).
        failingServer = new MockWebServer();
        failingServer.start();
        failingServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"message\":\"server error\"}");
            }
        });
        failingClient = new PagarMePayoutProviderClient(properties(failingServer));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
        failingServer.shutdown();
    }

    private static PspProperties properties(MockWebServer server) {
        PspProperties properties = new PspProperties();
        properties.getWebhook().setSecret("webhook-secret");
        properties.getPayoutPagarme().setSecretKey(SECRET_KEY);
        properties.getPayoutPagarme().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        return properties;
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    // ---------- Harness de contrato (Spec 38) ----------

    @Override
    protected PayoutProviderClient client() {
        return client;
    }

    @Override
    protected PayoutProviderClient failingClient() {
        return failingClient;
    }

    @Override
    protected PayoutProviderClient.PayoutCommand command() {
        return new PayoutProviderClient.PayoutCommand(
                UUID.fromString("8fdc7640-f6fa-47d0-a49f-4a722b125a74"),
                Money.of(new BigDecimal("40.00")),
                new PayoutProviderClient.PayoutDestination("52998224725", null, null, null, null),
                "saque lojista");
    }

    // ---------- Contrato v5 (auth, body, mapeamento) ----------

    @Test
    void createsTransferWithCorrectAuthAndBody() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(jsonResponse("{\"id\":\"tr_123\",\"status\":\"pending\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        PayoutProviderClient.PayoutResult result = c.createPayout(command());

        RecordedRequest request = s.takeRequest();
        String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(request.getHeader("Authorization")).isEqualTo(expectedAuth);
        assertThat(request.getPath()).isEqualTo("/transfers");

        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        assertThat(body.get("code").asText()).isEqualTo("8fdc7640-f6fa-47d0-a49f-4a722b125a74");
        assertThat(body.get("amount").asLong()).isEqualTo(4000); // R$ 40,00
        assertThat(body.get("pix").get("pix_key").asText()).isEqualTo("52998224725");
        assertThat(body.get("pix").get("pix_key_type").asText()).isEqualTo("cpf");

        assertThat(result.externalReference()).isEqualTo("tr_123");
        s.shutdown();
    }

    @Test
    void throwsOnApiError() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(new MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"invalid pix key\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        assertThatThrownBy(() -> c.createPayout(command()))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("Pagar.me: falha ao solicitar PIX de saida");
        s.shutdown();
    }

    @Test
    void throwsWhenResponseMissingId() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(jsonResponse("{\"status\":\"pending\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        assertThatThrownBy(() -> c.createPayout(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sem id");
        s.shutdown();
    }

    @Test
    void rejectsBankAccountDestination() {
        // BANK_ACCOUNT (TED) não é suportado — exigiria document_number (Spec 47).
        assertThatThrownBy(() -> client.createPayout(new PayoutProviderClient.PayoutCommand(
                UUID.randomUUID(), Money.of("40.00"),
                new PayoutProviderClient.PayoutDestination(null, "341", "0001", "1234567", "cc"),
                "saque")))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("BANK_ACCOUNT/TED nao suportado");
    }

    @Test
    void mapsGetPayoutStatuses() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"processed\"}"));
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"failed\"}"));
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"canceled\"}"));
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"pending\"}"));
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"in_process\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        assertThat(c.getPayout("tr_1")).isEqualTo(PayoutProviderClient.PayoutStatus.PAID);
        assertThat(c.getPayout("tr_1")).isEqualTo(PayoutProviderClient.PayoutStatus.FAILED);
        assertThat(c.getPayout("tr_1")).isEqualTo(PayoutProviderClient.PayoutStatus.FAILED);
        assertThat(c.getPayout("tr_1")).isEqualTo(PayoutProviderClient.PayoutStatus.PENDING);
        assertThat(c.getPayout("tr_1")).isEqualTo(PayoutProviderClient.PayoutStatus.PENDING);

        // Requisição: GET /transfers/{id}
        RecordedRequest request = s.takeRequest();
        assertThat(request.getPath()).isEqualTo("/transfers/tr_1");
        s.shutdown();
    }

    @Test
    void throwsWhenGetPayoutApiError() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(new MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"transfer not found\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        assertThatThrownBy(() -> c.getPayout("tr_nao_existe"))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("Pagar.me: falha ao consultar transfer");
        s.shutdown();
    }

    // ---------- Inferência do tipo da chave (pix_key_type) ----------

    @Test
    void infersPixKeyTypeFromCanonicalFormat() {
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("52998224725")).isEqualTo("cpf");
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("529.982.247-25")).isEqualTo("cpf"); // com pontuação
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("12345678000195")).isEqualTo("cnpj");
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("12.345.678/0001-95")).isEqualTo("cnpj");
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("+5511999999999")).isEqualTo("phone");
        assertThat(PagarMePayoutProviderClient.inferPixKeyType("123e4567-e89b-12d3-a456-426614174000")).isEqualTo("evp");
    }

    @Test
    void throwsWhenPixKeyTypeUnknown() {
        assertThatThrownBy(() -> PagarMePayoutProviderClient.inferPixKeyType("abc"))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("tipo desconhecido");
    }

    @Test
    void amountConvertsToLongCents() throws Exception {
        MockWebServer s = new MockWebServer();
        s.start();
        s.enqueue(jsonResponse("{\"id\":\"tr_1\",\"status\":\"pending\"}"));
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(properties(s));

        c.createPayout(new PayoutProviderClient.PayoutCommand(
                UUID.randomUUID(), Money.of(new BigDecimal("21474836.47")),
                new PayoutProviderClient.PayoutDestination("52998224725", null, null, null, null),
                "teto"));

        RecordedRequest request = s.takeRequest();
        JsonNode body = JSON.readTree(request.getBody().readUtf8());
        // B1 (auditoria 2026-08-05): centavos em long — sem estouro acima de R$ 21,4M.
        assertThat(body.get("amount").asLong()).isEqualTo(2147483647L);
        s.shutdown();
    }

    @Test
    void appliesConnectAndReadTimeouts() throws Exception {
        // L71/L72: timeouts 5s/10s aplicados ao request factory (mutantes que
        // removem os setters deixariam o default -1/infinito).
        PspProperties props = new PspProperties();
        props.getPayoutPagarme().setSecretKey(SECRET_KEY);
        props.getPayoutPagarme().setBaseUrl("https://api.pagar.me/core/v5");
        PagarMePayoutProviderClient c = new PagarMePayoutProviderClient(props);

        java.lang.reflect.Field restField = PagarMePayoutProviderClient.class.getDeclaredField("restClient");
        restField.setAccessible(true);
        Object restClient = restField.get(c);

        java.lang.reflect.Field factoryField = restClient.getClass().getDeclaredField("clientRequestFactory");
        factoryField.setAccessible(true);
        Object factory = factoryField.get(restClient);

        java.lang.reflect.Field connectField = factory.getClass().getDeclaredField("connectTimeout");
        connectField.setAccessible(true);
        java.lang.reflect.Field readField = factory.getClass().getDeclaredField("readTimeout");
        readField.setAccessible(true);

        assertThat(connectField.getInt(factory)).isEqualTo(5000);
        assertThat(readField.getInt(factory)).isEqualTo(10000);
    }

    @Test
    void masksUnknownKeyInErrorMessage() {
        // L178/L179: chave curta (≤4) vira "***" completa; o boundary (`<=` →
        // `<`) e o negate deixariam a chave "12***34" no lugar de "***".
        assertThatThrownBy(() -> PagarMePayoutProviderClient.inferPixKeyType("123"))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("desconhecido: ***");
        assertThatThrownBy(() -> PagarMePayoutProviderClient.inferPixKeyType("1234"))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("desconhecido: ***");
        // Chave longa: só as bordas aparecem (nunca a chave em claro).
        assertThatThrownBy(() -> PagarMePayoutProviderClient.inferPixKeyType("abcdef123456"))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("desconhecido: ab***56");
    }
}
