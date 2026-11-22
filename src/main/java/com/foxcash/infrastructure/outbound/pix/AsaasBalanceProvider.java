package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
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
import java.time.Duration;

/**
 * Saldo da conta master no **Asaas** (API v3) — Spec 92: {@code GET
 * {base-url}/finance/balance} com o token da API (a MESMA credencial de
 * cobrança). Mapeia {@code balance} → available e {@code blockedBalance} →
 * blocked. Registrado quando {@code provider=asaas}.
 *
 * <p>Auth via header {@code access_token} — MESMO padrão do
 * {@link AsaasPixProviderClient} (o Asaas aceita o token neste header;
 * consistência interna do adquirente — achado do audit 2026-08-14).
 */
@Component
@ConditionalOnProperty(prefix = "foxcash.psp", name = "provider",
        havingValue = "asaas", matchIfMissing = false)
public class AsaasBalanceProvider implements PspBalanceProvider {

    private static final Logger log = LoggerFactory.getLogger(AsaasBalanceProvider.class);

    private final RestClient restClient;

    public AsaasBalanceProvider(PspProperties properties) {
        PspProperties.Asaas asaas = properties.getAsaas();
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
    public PspBalance getBalance() {
        try {
            BalanceResponse response = restClient.get()
                    .uri("/finance/balance")
                    .retrieve()
                    .body(BalanceResponse.class);
            if (response == null || response.balance() == null) {
                throw new IllegalStateException("Asaas: resposta de balance invalida");
            }
            log.info("Asaas: balance available={} blocked={}",
                    response.balance(), response.blockedBalance());
            return new PspBalance(response.balance(),
                    response.blockedBalance() == null ? BigDecimal.ZERO : response.blockedBalance(),
                    "BRL");
        } catch (ResourceAccessException e) {
            throw new PspBalanceException("Asaas: timeout ao consultar balance", e);
        } catch (RestClientException e) {
            throw new PspBalanceException("Asaas: falha ao consultar balance", e);
        }
    }

    /** GET /finance/balance response (Asaas v3) — valores em reais. */
    record BalanceResponse(BigDecimal balance, BigDecimal blockedBalance,
                           BigDecimal transferredBalance) {
    }
}
