package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link PagarMeBalanceProvider} (Spec 92) contra um MockWebServer
 * que reproduz o contrato da API v5 do Pagar.me (GET /balance, auth Basic):
 * soma de centavos dos itens {@code available[]} → available e
 * {@code waiting_funds[]} → blocked; timeout/falha → {@code PspBalanceException}.
 * Herda o contrato {@link PspBalanceContractTest}.
 */
class PagarMeBalanceProviderTest extends PspBalanceContractTest {

    private static final String SECRET_KEY = "sk_test_123456";

    private MockWebServer server;
    private MockWebServer downServer;
    private PagarMeBalanceProvider client;
    private PagarMeBalanceProvider downClient;

    /** Corpo do GET /balance — cada teste define ANTES da chamada. */
    private MockResponse nextResponse = jsonResponse("{\"available\":[{\"id\":\"a1\",\"amount\":100}]}");

    @BeforeEach
    void setUp() throws Exception {
        // Dispatcher: GET /balance → nextResponse (default = available válido,
        // cobre os invariantes do harness abstrato).
        server = new MockWebServer();
        server.start();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return nextResponse;
            }
        });
        client = new PagarMeBalanceProvider(properties(server));

        // downServer: derrubado (connection refused) → ResourceAccessException
        // → PspBalanceException (timeout/falha de rede).
        downServer = new MockWebServer();
        downServer.start();
        downClient = new PagarMeBalanceProvider(properties(downServer));
        downServer.shutdown();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static PspProperties properties(MockWebServer server) {
        PspProperties properties = new PspProperties();
        properties.getPagarme().setSecretKey(SECRET_KEY);
        properties.getPagarme().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        return properties;
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Override
    protected PspBalanceProvider client() {
        return client;
    }

    @Test
    void getBalanceSomaCentavosDoAvailableEWaitingFunds() throws Exception {
        nextResponse = jsonResponse("""
                {"available":[{"id":"a1","amount":100050},{"id":"a2","amount":19950}],
                 "waiting_funds":[{"id":"w1","amount":5000}]}
                """);

        PspBalanceProvider.PspBalance balance = client.getBalance();

        // 100050 + 19950 centavos = 1200.00; waiting 5000 centavos = 50.00
        assertThat(balance.available()).isEqualByComparingTo("1200.00");
        assertThat(balance.blocked()).isEqualByComparingTo("50.00");
        assertThat(balance.currency()).isEqualTo("BRL");

        // Auth Basic: base64(secretKey:)
        RecordedRequest request = server.takeRequest();
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
        assertThat(request.getHeader("Authorization")).isEqualTo(expectedAuth);
        assertThat(request.getPath()).isEqualTo("/balance");
    }

    @Test
    void getBalanceSemWaitingFundsDevolveBlockedZero() throws Exception {
        nextResponse = jsonResponse("{\"available\":[{\"id\":\"a1\",\"amount\":100}]}");

        PspBalanceProvider.PspBalance balance = client.getBalance();

        assertThat(balance.available()).isEqualByComparingTo("1.00");
        assertThat(balance.blocked()).isEqualByComparingTo("0");
    }

    @Test
    void getBalanceComRespostaInvalidaLancaExcecao() throws Exception {
        nextResponse = jsonResponse("{}");

        assertThatThrownBy(client::getBalance)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void getBalanceComServidorDerrubadoLancaPspBalanceException() {
        assertThatThrownBy(downClient::getBalance)
                .isInstanceOf(PspBalanceProvider.PspBalanceException.class);
    }
}
