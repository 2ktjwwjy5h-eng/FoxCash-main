package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.config.RoutingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Política de roteamento por risco do lojista (Spec 91 — D4.1): tier pelo
 * {@code merchant_risk.risk_score} (Spec 19) → lista de prioridade; sem snapshot
 * → default; faixas ordenadas por {@code score-min} desc; sem policies → lista
 * global.
 */
class RoutingPolicyResolverTest {

    private final RiskRepository riskRepository = mock(RiskRepository.class);
    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final ProviderCatalog providerCatalog = mock(ProviderCatalog.class);

    private RoutingPolicyResolver resolver(RoutingProperties props) {
        return new RoutingPolicyResolver(props, riskRepository, profileRepository, providerCatalog);
    }

    private void withScore(UUID merchantId, int score) {
        MerchantRiskSnapshot snapshot = new MerchantRiskSnapshot(merchantId, Instant.now(),
                Money.of(new BigDecimal("1000")), 10, 8, 1, new BigDecimal("10"),
                new BigDecimal("5"), 0, false, true, false, score);
        when(riskRepository.latestByMerchant(merchantId)).thenReturn(Optional.of(snapshot));
    }

    private RoutingProperties policies(String defaultProviders, String highRiskProviders) {
        RoutingProperties props = new RoutingProperties();
        RoutingProperties.Policy defaultPolicy = new RoutingProperties.Policy();
        defaultPolicy.setProviders(defaultProviders);
        props.getPolicies().put("default", defaultPolicy);
        RoutingProperties.Policy highRisk = new RoutingProperties.Policy();
        highRisk.setProviders(highRiskProviders);
        highRisk.setScoreMin(60);
        props.getPolicies().put("high-risk", highRisk);
        return props;
    }

    @Test
    void semSnapshotVaiParaDefault() {
        UUID merchant = UUID.randomUUID();
        when(riskRepository.latestByMerchant(merchant)).thenReturn(Optional.empty());
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("default");
        assertThat(resolver.resolveProviders(merchant)).containsExactly("pagarme", "asaas");
    }

    @Test
    void scoreAbaixoDoLimiarVaiParaDefault() {
        UUID merchant = UUID.randomUUID();
        withScore(merchant, 30);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("default");
        assertThat(resolver.resolveProviders(merchant)).containsExactly("pagarme", "asaas");
    }

    @Test
    void scoreAcimaDoLimiarVaiParaHighRisk() {
        UUID merchant = UUID.randomUUID();
        withScore(merchant, 85);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("high-risk");
        // Isolamento por nicho: o lojista high-risk NUNCA cai nos adquirentes white.
        assertThat(resolver.resolveProviders(merchant)).containsExactly("suitpay", "asaas");
    }

    @Test
    void limiarExatoAplicaAPolicy() {
        UUID merchant = UUID.randomUUID();
        withScore(merchant, 60); // score-min exato
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("high-risk");
    }

    @Test
    void faixasOrdenadasPorScoreMinDescVenceAMaisRestritiva() {
        UUID merchant = UUID.randomUUID();
        withScore(merchant, 70);
        RoutingProperties props = new RoutingProperties();
        RoutingProperties.Policy low = new RoutingProperties.Policy();
        low.setProviders("pagarme");
        low.setScoreMin(20);
        props.getPolicies().put("low", low);
        RoutingProperties.Policy high = new RoutingProperties.Policy();
        high.setProviders("suitpay");
        high.setScoreMin(60);
        props.getPolicies().put("high", high);

        RoutingPolicyResolver resolver = resolver(props);

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("high"); // 70 >= 60 e 70 >= 20
        assertThat(resolver.resolveProviders(merchant)).containsExactly("suitpay");
    }

    @Test
    void semPoliciesUsaListaGlobal() {
        UUID merchant = UUID.randomUUID();
        when(riskRepository.latestByMerchant(merchant)).thenReturn(Optional.empty());
        RoutingProperties props = new RoutingProperties();
        props.setProviders("pagarme,asaas");

        RoutingPolicyResolver resolver = resolver(props);

        assertThat(resolver.resolvePolicyName(merchant)).isEqualTo("default");
        assertThat(resolver.resolveProviders(merchant)).containsExactly("pagarme", "asaas");
    }

    @Test
    void providersTratadosEmLowerCase() {
        UUID merchant = UUID.randomUUID();
        when(riskRepository.latestByMerchant(merchant)).thenReturn(Optional.empty());
        RoutingPolicyResolver resolver = resolver(policies("PAGARME, Asaas", "suitpay,ASAAS"));

        assertThat(resolver.resolveProviders(merchant)).containsExactly("pagarme", "asaas");
    }

    // ---- Spec 122: override por perfil de operação vence a política de risco ----

    @Test
    void overrideDoPerfilVenceRisco() {
        UUID merchant = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        withScore(merchant, 85); // high-risk (suitpay,asaas) — mas o override fixa pagarme
        when(profileRepository.gatewayRouteOf(merchant, profile)).thenReturn(Optional.of("pagarme"));
        when(providerCatalog.contains("pagarme")).thenReturn(true);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolveProviders(merchant, profile)).containsExactly("pagarme");
        assertThat(resolver.resolvePolicyName(merchant, profile)).isEqualTo("override");
    }

    @Test
    void overrideForaDoCatalogoFazFallbackParaRisco() {
        UUID merchant = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        withScore(merchant, 30); // default (pagarme,asaas)
        when(profileRepository.gatewayRouteOf(merchant, profile)).thenReturn(Optional.of("stripe"));
        when(providerCatalog.contains("stripe")).thenReturn(false); // sem adapter (Stripe é futuro)
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        // Defesa em profundidade: nunca 500 — cai para a política por risco.
        assertThat(resolver.resolveProviders(merchant, profile)).containsExactly("pagarme", "asaas");
        assertThat(resolver.resolvePolicyName(merchant, profile)).isEqualTo("default");
    }

    @Test
    void profileIdNullUsaRisco() {
        UUID merchant = UUID.randomUUID();
        withScore(merchant, 30);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        // Call-sites da fase 1 (cobrança sem perfil) — regressão Spec 91.
        assertThat(resolver.resolveProviders(merchant, null)).containsExactly("pagarme", "asaas");
    }

    // ---- Spec 139: defesa por escopo — BOT_ONLY nunca aplica override ----

    @Test
    void botOnlyNuncaAplicaOverrideMesmoComRotaStale() {
        UUID merchant = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        withScore(merchant, 85); // high-risk (suitpay,asaas)
        // Rota legada/stale no banco + escopo BOT_ONLY → override IGNORADO.
        when(profileRepository.scopeOf(merchant, profile)).thenReturn(Optional.of(ProfileScope.BOT_ONLY));
        when(profileRepository.gatewayRouteOf(merchant, profile)).thenReturn(Optional.of("pagarme"));
        when(providerCatalog.contains("pagarme")).thenReturn(true);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolveProviders(merchant, profile)).containsExactly("suitpay", "asaas");
        assertThat(resolver.resolvePolicyName(merchant, profile)).isEqualTo("high-risk");
    }

    @Test
    void botOnlyComScoreBaixoUsaDefaultPorRisco() {
        UUID merchant = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        withScore(merchant, 30); // default (pagarme,asaas)
        when(profileRepository.scopeOf(merchant, profile)).thenReturn(Optional.of(ProfileScope.BOT_ONLY));
        when(profileRepository.gatewayRouteOf(merchant, profile)).thenReturn(Optional.of("pagarme"));
        when(providerCatalog.contains("pagarme")).thenReturn(true);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolveProviders(merchant, profile)).containsExactly("pagarme", "asaas");
        assertThat(resolver.resolvePolicyName(merchant, profile)).isEqualTo("default");
    }

    @Test
    void generalMantemOverrideVencendoRisco() {
        UUID merchant = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        withScore(merchant, 85);
        when(profileRepository.scopeOf(merchant, profile)).thenReturn(Optional.of(ProfileScope.GENERAL));
        when(profileRepository.gatewayRouteOf(merchant, profile)).thenReturn(Optional.of("pagarme"));
        when(providerCatalog.contains("pagarme")).thenReturn(true);
        RoutingPolicyResolver resolver = resolver(policies("pagarme,asaas", "suitpay,asaas"));

        assertThat(resolver.resolveProviders(merchant, profile)).containsExactly("pagarme");
        assertThat(resolver.resolvePolicyName(merchant, profile)).isEqualTo("override");
    }
}
