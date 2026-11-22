package com.foxcash.infrastructure.security;

import com.foxcash.domain.exception.TurnstileFailedException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link TurnstileVerifier} (Spec 63, Fase C) contra um MockWebServer
 * que reproduz o contrato do siteverify do Cloudflare.
 *
 * <p>Pontos críticos cobertos:
 * <ul>
 *   <li>feature OFF (default) ou sem secret → validação desligada (passa sem token);</li>
 *   <li>token ausente/vazio com feature ON → {@code TurnstileFailedException};</li>
 *   <li>token válido (success=true) → passa; body do siteverify carrega secret+response;</li>
 *   <li>token rejeitado (success=false) → {@code TurnstileFailedException};</li>
 *   <li>API do Cloudflare indisponível → <b>fail-open</b> (passa, disponibilidade primeiro).</li>
 * </ul>
 */
class TurnstileVerifierTest {

    private MockWebServer server;
    private TurnstileVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        verifier = verifier(true, "secret-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private TurnstileVerifier verifier(boolean enabled, String secretKey) {
        TurnstileProperties properties = new TurnstileProperties();
        properties.setEnabled(enabled);
        properties.setSecretKey(secretKey);
        properties.setSiteKey("sitekey-test");
        properties.setVerifyUrl(server.url("/siteverify").toString());
        return new TurnstileVerifier(properties);
    }

    @Test
    void disabledFeatureNeverBlocks() {
        verifier = verifier(false, "secret-test");
        // Feature off (default dev/CI): sem token → passa.
        verifier.verify(null);
        verifier.verify("");
    }

    @Test
    void missingSecretKeyDisablesValidation() {
        verifier = verifier(true, "");
        verifier.verify(null);
        verifier.verify("qualquer-coisa");
    }

    @Test
    void missingTokenRejectedWhenActive() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(TurnstileFailedException.class);
    }

    @Test
    void blankTokenRejectedWhenActive() {
        assertThatThrownBy(() -> verifier.verify("   "))
                .isInstanceOf(TurnstileFailedException.class);
    }

    @Test
    void validTokenAcceptedAndBodyCarriesSecretAndResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"challenge_ts\":\"2026-08-09T20:00:00Z\"}"));

        verifier.verify("token-valido");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/siteverify");
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("secret=secret-test");
        assertThat(body).contains("response=token-valido");
    }

    @Test
    void rejectedTokenThrows() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}"));

        assertThatThrownBy(() -> verifier.verify("token-invalido"))
                .isInstanceOf(TurnstileFailedException.class);
    }

    @Test
    void http400FromSiteVerifyRejected() {
        // siteverify com secret inválido responde HTTP 400 (invalid-input-secret) —
        // a API está ACESSÍVEL e NÃO validou → rejeita (achado do teste ao vivo
        // 2026-08-10: antes, qualquer RuntimeException virava fail-open e um secret
        // inválido desligava a proteção silenciosamente).
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":false,\"error-codes\":[\"invalid-input-secret\"]}"));

        assertThatThrownBy(() -> verifier.verify("token-qualquer"))
                .isInstanceOf(TurnstileFailedException.class);
    }

    @Test
    void cloudflareDownFailsOpen() {
        // API do Cloudflare com erro 500 → fail-open (passa) + WARN throttled:
        // uma queda do Turnstile nunca derruba o login (ADR-010).
        server.enqueue(new MockResponse().setResponseCode(500));

        verifier.verify("token-qualquer");
    }

    @Test
    void malformedResponseFailsOpen() {
        // Resposta inesperada (não-JSON) → fail-open (passa).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("proxy error"));

        verifier.verify("token-qualquer");
    }
}
