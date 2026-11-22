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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link AsaasBalanceProvider} (Spec 92) contra um MockWebServer que
 * reproduz o contrato da API v3 do Asaas (GET /finance/balance, auth Bearer):
 * {@code balance} → available e {@code blockedBalance} → blocked; timeout/falha
 * → {@code PspBalanceException}. Herda o contrato {@link PspBalanceContractTest}.
 */
class AsaasBalanceProviderTest extends PspBalanceContractTest {

    private static final String SECRET_KEY = "sk_test_123456";

    private MockWebServer server;
    private MockWebServer downServer;
    private AsaasBalanceProvider client;
    private AsaasBalanceProvider downClient;

    /** Corpo do GET /finance/balance — cada teste define ANTES da chamada. */
    private MockResponse nextResponse =
            jsonResponse("{\"balance\":1.00,\"blockedBalance\":0,\"transferredBalance\":0}");

    @BeforeEach
    void setUp() throws Exception {
        // Dispatcher: GET /finance/balance → nextResponse (default = balance
        // válido, cobre os invariantes do harness abstrato).
        server = new MockWebServer();
        server.start();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return nextResponse;
            }
        });
        client = new AsaasBalanceProvider(properties(server));

        // downServer: derrubado (connection refused) → PspBalanceException.
        downServer = new MockWebServer();
        downServer.start();
        downClient = new AsaasBalanceProvider(properties(downServer));
        downServer.shutdown();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static PspProperties properties(MockWebServer server) {
        PspProperties properties = new PspProperties();
        properties.getAsaas().setSecretKey(SECRET_KEY);
        properties.getAsaas().setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
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
    void getBalanceMapeiaBalanceEBlockedBalance() throws Exception {
        nextResponse = jsonResponse("""
                {"balance":1234.56,"blockedBalance":10.00,"transferredBalance":0}
                """);

        PspBalanceProvider.PspBalance balance = client.getBalance();

        assertThat(balance.available()).isEqualByComparingTo("1234.56");
        assertThat(balance.blocked()).isEqualByComparingTo("10.00");
        assertThat(balance.currency()).isEqualTo("BRL");

        // Auth via access_token: token da API v3 (mesmo padrão do AsaasPixProviderClient).
        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("access_token")).isEqualTo(SECRET_KEY);
        assertThat(request.getPath()).isEqualTo("/finance/balance");
    }

    @Test
    void getBalanceSemBlockedDevolveZero() throws Exception {
        nextResponse = jsonResponse("{\"balance\":42.00}");

        PspBalanceProvider.PspBalance balance = client.getBalance();

        assertThat(balance.available()).isEqualByComparingTo("42.00");
        assertThat(balance.blocked()).isEqualByComparingTo(BigDecimal.ZERO);
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
