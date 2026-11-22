package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.RoutingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roteador de cobrança Pix por adquirente (Spec 91 — D4). Implementa
 * {@link PixProviderClient} e é o bean {@code @Primary}: os adapters são beans
 * SEMPRE (Spec 91) e o router decide a ordem — com
 * {@code foxcash.psp.routing.enabled=false} usa apenas o {@code provider}
 * configurado (comportamento atual preservado).
 *
 * <p><b>Ordem (D4.1):</b> com routing ligado, a lista vem da
 * {@code RoutingPolicyResolver} (política por risco do lojista —
 * {@code routing.policies.*}); sem policies, a lista global
 * ({@code routing.providers}). Provedor listado sem bean → fail-fast na
 * construção (config quebrada não silencia).
 *
 * <p><b>createCharge (D4):</b> tenta os provedores em ordem; erro DEFINITIVO
 * (4xx de negócio — {@code PixProviderException}) → fallback imediato; erro
 * INCERTO (timeout/5xx — {@code PixProviderTimeoutException}) → **poll
 * anti-dupla-cobrança** ({@code getChargeByCode} no A, até 2 tentativas): achou →
 * re-`createCharge` no A (retry idempotente Spec 28 — mesma cobrança, sem
 * duplicar); não achou (ou consulta incerta) → tenta B. Todos falham → exceção
 * do último (fluxo Spec 28 preservado: 502/PENDING ou FAILED).
 *
 * <p><b>Circuit breaker (D5):</b> provedor com {@code shouldSkip} (circuito
 * OPEN/kill-switch/por-lojista) é PULADO na ordenação — sem nem tentar.
 */
@Component
@Primary
public class PixProviderRouter implements PixProviderClient {

    private static final Logger log = LoggerFactory.getLogger(PixProviderRouter.class);

    private final List<OrderedProvider> orderedProviders;
    private final PspProperties pspProperties;
    private final RoutingProperties routingProperties;
    private final RoutingPolicyResolver policyResolver;
    private final PixProviderHealthRegistry healthRegistry;
    private final MeterRegistry meterRegistry;
    // Spec 98 (D4.5): honeypot — o trap vence QUALQUER policy/perfil/circuito.
    private final com.foxcash.domain.port.out.MerchantConfigProvider merchantConfigProvider;
    private final org.springframework.beans.factory.ObjectProvider<HoneypotPixProviderClient> honeypotProvider;
    private final com.foxcash.application.service.HoneypotAlertPublisher honeypotAlerts;

    /** Provedor + nome de roteamento (chave da config, ex.: "pagarme"). */
    private record OrderedProvider(PixProviderClient client, String name) {
    }

    public PixProviderRouter(Map<String, PixProviderClient> providers,
                             PspProperties properties,
                             RoutingProperties routingProperties,
                             RoutingPolicyResolver policyResolver,
                             PixProviderHealthRegistry healthRegistry,
                             MeterRegistry meterRegistry,
                             com.foxcash.domain.port.out.MerchantConfigProvider merchantConfigProvider,
                             org.springframework.beans.factory.ObjectProvider<HoneypotPixProviderClient> honeypotProvider,
                             com.foxcash.application.service.HoneypotAlertPublisher honeypotAlerts) {
        this.pspProperties = properties;
        this.routingProperties = routingProperties;
        this.policyResolver = policyResolver;
        this.healthRegistry = healthRegistry;
        this.meterRegistry = meterRegistry;
        this.merchantConfigProvider = merchantConfigProvider;
        this.honeypotProvider = honeypotProvider;
        this.honeypotAlerts = honeypotAlerts;

        List<String> keys = routingKeys(null, properties);
        this.orderedProviders = new ArrayList<>(keys.size());
        for (String key : keys) {
            PixProviderClient provider = findProvider(providers, key);
            if (provider == null) {
                throw new IllegalStateException(
                        "PixProviderRouter: provedor de cobranca '" + key
                                + "' listado na config nao tem adapter (beans: "
                                + String.join(", ", providers.keySet()) + ")");
            }
            // Fail-fast de config (Spec 91, critério 8 — achado do audit): o
            // PspProperties.validate() só cobre o provider CONFIGURADO; com o
            // routing, adquirente real listado (pagarme/asaas) sem credencial
            // válida (null/blank/placeholder não-resolvido) quebra o boot — antes
            // só falharia em runtime (401 no PSP).
            validateSecret(key, properties);
            orderedProviders.add(new OrderedProvider(provider, key));
        }
        if (orderedProviders.isEmpty()) {
            throw new IllegalStateException(
                    "PixProviderRouter: nenhum provedor de cobranca configurado (provider ou routing.providers)");
        }
        log.info("PixProviderRouter: ordem de provedores de cobranca = {}",
                orderedProviders.stream().map(OrderedProvider::name).toList());
    }

    @Override
    public CreateChargeResult createCharge(CreateChargeCommand command) {
        UUID merchantId = command.merchantId();
        // Spec 98 (D4.5): honeypot — a cobrança do lojista em ambiente controlado
        // roteia para o TRAP ANTES de qualquer policy/perfil/circuito (o atacante
        // segue vendo a operação; nenhum crédito real existe). Falha fechada:
        // lojista honeypot SEM trap (kill-switch desligado) → erro (nunca cai
        // para o adquirente real — revelaria o ambiente).
        if (isHoneypot(merchantId)) {
            HoneypotPixProviderClient trap = honeypotProvider.getIfAvailable();
            if (trap == null) {
                throw new PixProviderException(
                        "PixProviderRouter: lojista honeypot sem provedor trap (foxcash.psp.honeypot-enabled=false)");
            }
            // Alerta ao operador com throttle (padrão Spec 64) — o atacante não é alertado.
            honeypotAlerts.record(merchantId, command.transactionId());
            return trap.createCharge(command);
        }
        // Spec 124 (rota viva): o perfil da cobrança (quando presente) vence a
        // política por risco — resolveProviders(merchantId, profileId) (Spec 122).
        UUID profileId = command.profileId();
        List<String> keys = routingKeys(merchantId, profileId, null);
        String policy = policyResolver.resolvePolicyName(merchantId, profileId);

        RuntimeException last = null;
        for (String key : keys) {
            PixProviderClient provider = findByName(key);
            // Circuit breaker preventivo (D5): circuito aberto/kill-switch/por-lojista → pula.
            if (healthRegistry.shouldSkip(key) || healthRegistry.shouldSkipForMerchant(key, merchantId)) {
                meter("foxcash_psp_route_total", key, policy, "skipped").increment();
                log.warn("PixProviderRouter: provedor '{}' pulado (circuito/saúde) para transacao={}",
                        key, command.transactionId());
                continue;
            }
            try {
                CreateChargeResult result = provider.createCharge(command);
                healthRegistry.recordSuccess(key);
                meter("foxcash_psp_route_total", key, policy, "ok").increment();
                meter("foxcash_psp_charge_created_total", key, null, null).increment();
                // Roteamento pós-fallback (Spec 91): o provedor que CRIOU é gravado
                // no resultado — o caller persiste em pix_transactions.provider.
                return new CreateChargeResult(result.externalReference(), result.pixQrCode(),
                        result.pixCopyPaste(), key);
            } catch (PixProviderTimeoutException e) {
                // Resposta INCERTA (timeout/5xx): o A PODE ter criado — poll anti-dupla-cobrança.
                healthRegistry.recordFailure(key);
                if (pollAchou(provider, command)) {
                    // A cobrança existe no A: re-cria (retry idempotente Spec 28 —
                    // o code = transactionId devolve a MESMA cobrança) e usa A.
                    healthRegistry.recordSuccess(key);
                    meter("foxcash_psp_route_total", key, policy, "poll").increment();
                    meter("foxcash_psp_charge_created_total", key, null, null).increment();
                    CreateChargeResult retry = provider.createCharge(command);
                    return new CreateChargeResult(retry.externalReference(), retry.pixQrCode(),
                            retry.pixCopyPaste(), key);
                }
                last = e;
                meter("foxcash_psp_route_total", key, policy, "fallback").increment();
                log.warn("PixProviderRouter: provedor '{}' falhou (incerto) ao criar cobranca "
                        + "(transacao={}) — tentando proximo: {}", key, command.transactionId(), e.getMessage());
            } catch (PixProviderException e) {
                // Erro DEFINITIVO (4xx de negócio): fallback imediato, sem poll.
                healthRegistry.recordFailure(key);
                last = e;
                meter("foxcash_psp_route_total", key, policy, "fallback").increment();
                log.warn("PixProviderRouter: provedor '{}' falhou (definitivo) ao criar cobranca "
                        + "(transacao={}) — tentando proximo: {}", key, command.transactionId(), e.getMessage());
            } catch (RuntimeException e) {
                // Falha inesperada do provedor (ex.: resposta malformada): trata como
                // falha do adquirente e tenta o próximo (não arrisca perder a venda).
                healthRegistry.recordFailure(key);
                last = e;
                meter("foxcash_psp_route_total", key, policy, "fallback").increment();
                log.warn("PixProviderRouter: provedor '{}' falhou inesperadamente (transacao={}): {}",
                        key, command.transactionId(), e.getMessage());
            }
        }
        log.error("PixProviderRouter: todos os provedores falharam para transacao={}", command.transactionId());
        throw last != null ? last : new PixProviderException("PixProviderRouter: sem provedor de cobranca");
    }

    @Override
    public GetChargeResult getChargeByCode(String code) {
        // Consulta direta (reconciliação/uso interno): tenta os provedores em
        // ordem até um reconhecer o code (o code é o transactionId — o provedor
        // que o gerou responde; os demais devolvem NOT_FOUND).
        RuntimeException last = null;
        for (OrderedProvider candidate : orderedProviders) {
            try {
                GetChargeResult result = candidate.client().getChargeByCode(code);
                if (result.found()) {
                    return result;
                }
            } catch (PixProviderTimeoutException e) {
                last = e;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        return new GetChargeResult(false, null, null, null);
    }

    /**
     * Poll anti-dupla-cobrança (D4): até 2 tentativas com pequeno backoff no A —
     * achou (QR) → true; NOT_FOUND → false; consulta incerta → tenta de novo.
     */
    private boolean pollAchou(PixProviderClient provider, CreateChargeCommand command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                GetChargeResult result = provider.getChargeByCode(command.transactionId().toString());
                if (result.found()) {
                    return true;
                }
                return false; // NOT_FOUND definitivo
            } catch (PixProviderTimeoutException e) {
                // Consulta incerta: não confirma que não criou — tenta de novo.
                if (attempt == 0) {
                    sleepQuietly(250);
                }
            }
        }
        return false; // não confirmou — trata como "não achou" (não arrisca dupla cobrança)
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Lista de prioridade: routing off → [provider]; on → política por risco. */
    private List<String> routingKeys(UUID merchantId, PspProperties properties) {
        return routingKeys(merchantId, null, properties);
    }

    /** Lojista em ambiente controlado (Spec 98 — D4.5): configuração consultada
     * por leitura direta (v1 sem cache — decisão registrada; medir se precisar). */
    private boolean isHoneypot(UUID merchantId) {
        return merchantConfigProvider.config(merchantId)
                .map(com.foxcash.domain.model.merchant.MerchantConfig::honeypot)
                .orElse(false);
    }

    /** Lista de prioridade com perfil de operação (Spec 124 — rota viva): o
     *  override do perfil vence a política por risco; null → comportamento atual. */
    private List<String> routingKeys(UUID merchantId, UUID profileId, PspProperties properties) {
        PspProperties effective = properties != null ? properties : pspProperties;
        if (!routingProperties.isEnabled()) {
            return List.of(effective.getProvider().toLowerCase());
        }
        List<String> fromPolicy = policyResolver.resolveProviders(merchantId, profileId);
        if (!fromPolicy.isEmpty()) {
            return fromPolicy;
        }
        String raw = routingProperties.getProviders();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "PixProviderRouter: routing.enabled=true exige routing.providers ou routing.policies");
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }

    private PixProviderClient findByName(String key) {
        for (OrderedProvider candidate : orderedProviders) {
            if (candidate.name().equals(key)) {
                return candidate.client();
            }
        }
        // Não deveria ocorrer (a lista é construída no construtor), mas falha fechada.
        throw new IllegalStateException("PixProviderRouter: provedor '" + key + "' fora da lista");
    }

    /** Deriva a chave do provedor a partir do nome do bean ({@code xxxPixProviderClient}). */
    private static PixProviderClient findProvider(Map<String, PixProviderClient> providers, String key) {
        for (Map.Entry<String, PixProviderClient> e : providers.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String beanName = e.getKey().toLowerCase();
            if (beanName.startsWith(key) && beanName.contains("pixproviderclient")) {
                return e.getValue();
            }
            if (e.getValue().getClass().getSimpleName().toLowerCase().startsWith(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private io.micrometer.core.instrument.Counter meter(String name, String provider,
                                                        String policy, String outcome) {
        io.micrometer.core.instrument.Counter.Builder builder =
                io.micrometer.core.instrument.Counter.builder(name)
                        .tag("provider", provider == null ? "none" : provider);
        if (policy != null) {
            builder.tag("policy", policy);
        }
        if (outcome != null) {
            builder.tag("outcome", outcome);
        }
        return builder.register(meterRegistry);
    }

    /**
     * Fail-fast de credencial (Spec 91, critério 8): adquirente REAL listado na
     * rota (pagarme/asaas) exige secret-key válida — null/blank/placeholder
     * não-resolvido ({@code ${...}}) quebra o boot (mesmo padrão do
     * {@code PspProperties.validate()} para o provider configurado). Mocks
     * (mock/suitpay) não precisam.
     */
    private static void validateSecret(String key, PspProperties psp) {
        switch (key) {
            case "pagarme" -> requireValidSecret(key, psp.getPagarme().getSecretKey());
            case "asaas" -> requireValidSecret(key, psp.getAsaas().getSecretKey());
            default -> {
                // Mocks (mock/suitpay/outros) não exigem credencial.
            }
        }
    }

    private static void requireValidSecret(String key, String secret) {
        if (secret == null || secret.isBlank() || secret.contains("${")) {
            throw new IllegalStateException(
                    "PixProviderRouter: provedor de cobranca '" + key
                            + "' sem credencial valida (secret-key obrigatoria quando o adquirente e usado)");
        }
    }
}
