package com.foxcash.infrastructure.outbound.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.tracking.ProviderResult;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Base dos clientes de tracking (Spec 81/D6) — RestClient com timeouts
 * (padrão Spec 11/38: Connect 5s / Read 10s) e helpers de POST/GET JSON que
 * NUNCA lançam (devolvem {@link ProviderResult} — o retry/backoff é do outbox).
 */
abstract class AbstractTrackingProviderClient {

    protected final ObjectMapper objectMapper;
    protected final RestClient restClient;

    protected AbstractTrackingProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    protected ProviderResult postJson(String url, Map<String, String> headers, Object body) {
        try {
            RestClient.RequestBodySpec spec = restClient.post().uri(url);
            headers.forEach(spec::header);
            String response = spec.contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(String.class);
            return evaluate(response);
        } catch (RestClientResponseException e) {
            return ProviderResult.failed("HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return ProviderResult.failed(message(e));
        }
    }

    protected ProviderResult get(String url, Map<String, String> headers) {
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(url);
            headers.forEach(spec::header);
            String response = spec.retrieve().body(String.class);
            return evaluate(response);
        } catch (RestClientResponseException e) {
            return ProviderResult.failed("HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return ProviderResult.failed(message(e));
        }
    }

    /** Sucesso = 2xx sem chave {@code error} no corpo (padrão das APIs de mídia). */
    private ProviderResult evaluate(String response) {
        if (response != null && !response.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(response);
                if (node.has("error")) {
                    JsonNode error = node.get("error");
                    String msg = error.isTextual() ? error.asText() : error.toString();
                    return ProviderResult.failed(truncate(msg));
                }
                if (node.has("ok") && !node.get("ok").asBoolean(true)) {
                    return ProviderResult.failed(truncate(response));
                }
            } catch (Exception ignored) {
                // corpo não-JSON: trata como sucesso 2xx
            }
        }
        return ProviderResult.ok(truncate(response));
    }

    protected static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
