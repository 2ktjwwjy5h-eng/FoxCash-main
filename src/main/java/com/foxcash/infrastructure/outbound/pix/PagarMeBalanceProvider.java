package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Saldo da conta master no **Pagar.me** (API v5) — Spec 92: {@code GET
 * {base-url}/balance} com autenticação Basic (a MESMA credencial de cobrança —
 * o saldo pertence à conta que recebe os Pix). O "Disponível" é a soma dos
 * itens {@code available[]} (centavos); {@code waiting_funds} fica como
 * {@code blocked} (informação). Registrado quando {@code provider=pagarme}.
 */
@Component
@ConditionalOnProperty(prefix = "foxcash.psp", name = "provider",
        havingValue = "pagarme", matchIfMissing = false)
public class PagarMeBalanceProvider implements PspBalanceProvider {

    private static final Logger log = LoggerFactory.getLogger(PagarMeBalanceProvider.class);

    private final RestClient restClient;

    public PagarMeBalanceProvider(PspProperties properties) {
        PspProperties.Pagarme pagarme = properties.getPagarme();
        String credentials = Base64.getEncoder().encodeToString(
                (pagarme.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));
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
    public PspBalance getBalance() {
        try {
            BalanceResponse response = restClient.get()
                    .uri("/balance")
                    .retrieve()
                    .body(BalanceResponse.class);
            if (response == null || response.available() == null) {
                throw new IllegalStateException("Pagar.me: resposta de balance invalida");
            }
            long availableCents = response.available().stream()
                    .mapToLong(BalanceItem::amount)
                    .sum();
            long blockedCents = response.waitingFunds() == null ? 0L
                    : response.waitingFunds().stream().mapToLong(BalanceItem::amount).sum();
            log.info("Pagar.me: balance available={} blocked={}", availableCents, blockedCents);
            return new PspBalance(
                    BigDecimal.valueOf(availableCents, 2),
                    BigDecimal.valueOf(blockedCents, 2),
                    "BRL");
        } catch (ResourceAccessException e) {
            throw new PspBalanceException("Pagar.me: timeout ao consultar balance", e);
        } catch (RestClientException e) {
            throw new PspBalanceException("Pagar.me: falha ao consultar balance", e);
        }
    }

    /** GET /balance response (Pagar.me v5) — itens com amount em centavos. */
    record BalanceResponse(List<BalanceItem> available,
                           @JsonProperty("waiting_funds") List<BalanceItem> waitingFunds) {
    }

    record BalanceItem(String id, long amount) {
    }
}
