package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.config.RoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve a politica de roteamento por risco do lojista (Spec 91 — critério 11,
 * D4.1): a partir do {@code merchant_risk.risk_score} (Spec 19, snapshot V9 —
 * coluna real {@code risk_score}), escolhe a lista de prioridade
 * ({@code foxcash.psp.routing.policies.*}).
 *
 * <ul>
 *   <li>{@code default} — lojista sem snapshot / score abaixo de todos os
 *       limiares → lista padrão;</li>
 *   <li>faixas com {@code score-min} (ex.: {@code high-risk: score-min 60}) —
 *       aplica quando {@code risk_score >= scoreMin}; faixas ordenadas por
 *       {@code score-min} desc (a mais restritiva vence);</li>
 *   <li>sem policies configuradas → lista global ({@code routing.providers});</li>
 *   <li>isolamento por nicho: as listas das policies são DISJUNTAS por design —
 *       o tráfego white nunca vai para os adquirentes do tier alto (e vice-versa).</li>
 * </ul>
 */
@Component
public class RoutingPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicyResolver.class);

    private static final String DEFAULT_POLICY = "default";

    private static final String OVERRIDE_POLICY = "override";

    private final RoutingProperties routingProperties;
    private final RiskRepository riskRepository;
    private final MerchantProfileRepository profileRepository;
    private final ProviderCatalog providerCatalog;

    public RoutingPolicyResolver(RoutingProperties routingProperties, RiskRepository riskRepository,
                                 MerchantProfileRepository profileRepository, ProviderCatalog providerCatalog) {
        this.routingProperties = routingProperties;
        this.riskRepository = riskRepository;
        this.profileRepository = profileRepository;
        this.providerCatalog = providerCatalog;
    }

    /**
     * Lista de provedores (ordem de prioridade) para o lojista — resolve a
     * política pelo score; sem policies, usa a lista global.
     */
    public List<String> resolveProviders(UUID merchantId) {
        Map<String, RoutingProperties.Policy> policies = routingProperties.getPolicies();
        if (policies == null || policies.isEmpty()) {
            return parse(routingProperties.getProviders());
        }
        String policyName = resolvePolicyName(merchantId);
        RoutingProperties.Policy policy = policies.get(policyName);
        if (policy == null || policy.getProviders() == null || policy.getProviders().isBlank()) {
            log.warn("RoutingPolicyResolver: policy '{}' sem providers — fallback para a lista global",
                    policyName);
            return parse(routingProperties.getProviders());
        }
        return parse(policy.getProviders());
    }

    /**
     * Lista de provedores para o lojista **no contexto de um perfil de operação**
     * (Spec 122 — fase 1): o override do perfil ({@code merchant_profiles.gateway_route})
     * **vence a política de risco**. {@code profileId} null (call-sites da fase 1)
     * → comportamento atual.
     *
     * <p>Defesa por escopo (Spec 139): perfil {@code BOT_ONLY} **nunca** aplica
     * override — o roteamento é sempre por risco, mesmo com rota legada/stale no
     * banco. GENERAL/GATEWAY_ONLY seguem o fluxo atual.</p>
     *
     * <p>Defesa em profundidade: rota do perfil fora do catálogo configurado
     * (ex.: {@code gateway_route = "stripe"} sem adapter/credencial) → fallback
     * para a política por risco + WARN (nunca 500). O PATCH/POST já impedem gravar
     * rota inválida (400 {@code unknown_gateway_route}); a checagem cobre dados
     * legados/edição manual.</p>
     */
    public List<String> resolveProviders(UUID merchantId, UUID profileId) {
        if (profileId != null && !isBotOnly(merchantId, profileId)) {
            Optional<String> override = profileRepository.gatewayRouteOf(merchantId, profileId);
            if (override.isPresent() && !override.get().isBlank()) {
                String route = override.get().toLowerCase();
                if (providerCatalog.contains(route)) {
                    return List.of(route);
                }
                log.warn("RoutingPolicyResolver: override do perfil '{}' com rota '{}' fora do catálogo "
                        + "— fallback para a política por risco", profileId, route);
            }
        }
        return resolveProviders(merchantId);
    }

    /** Nome da política resolvida para o lojista (auditoria via métrica). */
    public String resolvePolicyName(UUID merchantId) {
        Map<String, RoutingProperties.Policy> policies = routingProperties.getPolicies();
        if (policies == null || policies.isEmpty()) {
            return DEFAULT_POLICY;
        }
        int score = riskScore(merchantId);
        // Faixas ordenadas por score-min DESC — a mais restritiva vence.
        List<Map.Entry<String, RoutingProperties.Policy>> tiers = policies.entrySet().stream()
                .filter(e -> !DEFAULT_POLICY.equals(e.getKey()))
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, RoutingProperties.Policy> e) -> e.getValue().getScoreMin())
                        .reversed())
                .toList();
        for (Map.Entry<String, RoutingProperties.Policy> tier : tiers) {
            if (score >= tier.getValue().getScoreMin()) {
                return tier.getKey();
            }
        }
        return DEFAULT_POLICY;
    }

    /**
     * Nome da política no contexto de um perfil (Spec 122): {@code "override"}
     * quando o override do perfil está ativo (rota fixada e válida no catálogo);
     * senão a política por risco atual. Tag da métrica {@code foxcash_psp_route_total}.
     * Perfil {@code BOT_ONLY} (Spec 139) → nunca {@code "override"} (rota é sempre
     * por risco).
     */
    public String resolvePolicyName(UUID merchantId, UUID profileId) {
        if (profileId != null && !isBotOnly(merchantId, profileId)) {
            Optional<String> override = profileRepository.gatewayRouteOf(merchantId, profileId);
            if (override.isPresent() && !override.get().isBlank()
                    && providerCatalog.contains(override.get().toLowerCase())) {
                return OVERRIDE_POLICY;
            }
        }
        return resolvePolicyName(merchantId);
    }

    /**
     * Perfil BOT_ONLY (Spec 139)? Leitura leve (projection) — o scope é a defesa
     * do resolver: perfil de bot nunca aplica override de rota.
     */
    private boolean isBotOnly(UUID merchantId, UUID profileId) {
        return profileRepository.scopeOf(merchantId, profileId)
                .map(ProfileScope.BOT_ONLY::equals)
                .orElse(false);
    }

    /** Score do lojista (Spec 19); -1 quando não há snapshot (→ default). */
    private int riskScore(UUID merchantId) {
        MerchantRiskSnapshot snapshot = riskRepository.latestByMerchant(merchantId).orElse(null);
        return snapshot != null ? snapshot.riskScore() : -1;
    }

    private static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }
}
