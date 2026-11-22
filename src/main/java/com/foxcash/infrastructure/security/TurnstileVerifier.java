package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.foxcash.domain.exception.TurnstileFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Verificador server-side do Cloudflare Turnstile (Spec 63, Fase C).
 *
 * <p>Valida o token do widget via {@code siteverify}
 * ({@code POST https://challenges.cloudflare.com/turnstile/v0/siteverify},
 * {@code secret} + {@code response}, timeout 5s default). Aplicado nos
 * endpoints públicos de abuso: login do portal e cadastro de lojista.
 *
 * <p><b>Fail-open (disponibilidade primeiro, ADR-010):</b> se a API do
 * Turnstile estiver inacessível/timeout, a requisição <b>passa</b> com WARN
 * throttled (uma queda do Turnstile nunca derruba o login). Um token ausente
 * ou rejeitado pelo Cloudflare é falha de validação → {@link TurnstileFailedException}.
 *
 * <p>Desligado quando {@code enabled=false} ou {@code secret-key} ausente
 * (dev/CI/E2E passam sem token — default seguro).
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

    /** Throttle do WARN de fail-open: 1 a cada 60s (mesmo padrão do RedisRateLimitStore). */
    private static final long WARN_THROTTLE_MS = 60_000L;

    private final TurnstileProperties properties;
    private final RestClient restClient;
    private volatile long lastWarnAtMillis = 0L;

    public TurnstileVerifier(TurnstileProperties properties) {
        this.properties = properties;
        Duration timeout = properties.getTimeout();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Valida o token Turnstile do request. Não faz nada quando a feature está
     * desligada ou o secret não está configurado. Lança
     * {@link TurnstileFailedException} com token ausente/inválido. Fail-open
     * (WARN throttled) se a API do Cloudflare estiver indisponível.
     */
    public void verify(String token) {
        if (!isActive()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new TurnstileFailedException();
        }

        SiteVerifyResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody(token))
                    .retrieve()
                    .body(SiteVerifyResponse.class);
        } catch (HttpClientErrorException e) {
            // siteverify respondeu 4xx (ex.: 400 invalid-input-secret): a API está
            // ACESSÍVEL e NÃO validou o token → rejeita (segurança na validação —
            // não é indisponibilidade; achado do teste ao vivo 2026-08-10: antes,
            // qualquer RuntimeException virava fail-open e um secret inválido
            // desligava a proteção silenciosamente).
            log.warn("Turnstile siteverify rejeitou (HTTP {}): {}", e.getStatusCode().value(), e.getMessage());
            throw new TurnstileFailedException();
        } catch (RestClientException e) {
            // Fail-open (ADR-010): Turnstile INDISPONÍVEL (5xx, timeout, rede ou
            // resposta ilegível) → permite a requisição (uma queda do Cloudflare
            // nunca derruba o login). WARN throttled.
            warnThrottled("Turnstile siteverify indisponivel — fail-open (permitindo): " + e.getMessage());
            return;
        }

        if (response == null || !response.success()) {
            throw new TurnstileFailedException();
        }
    }

    /** True quando a validação deve rodar: enabled + secret key presente. */
    private boolean isActive() {
        return properties.isEnabled()
                && properties.getSecretKey() != null
                && !properties.getSecretKey().isBlank();
    }

    private MultiValueMap<String, String> formBody(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);
        return form;
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAtMillis > WARN_THROTTLE_MS) {
            lastWarnAtMillis = now;
            log.warn(message);
        }
    }

    /** Resposta do siteverify — {@code success} é o único campo consumido. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteVerifyResponse(boolean success) {
    }
}
