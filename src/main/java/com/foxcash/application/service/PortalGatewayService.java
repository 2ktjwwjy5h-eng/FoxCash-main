package com.foxcash.application.service;

import com.foxcash.domain.model.transaction.ProviderVolume;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayCatalogItemResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayOverviewResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayProviderResponse;
import com.foxcash.infrastructure.outbound.pix.PixProviderHealthRegistry;
import com.foxcash.infrastructure.outbound.pix.RoutingPolicyResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Visão de gateways do portal (Spec 107) — read-only, aditivo. NÃO toca o
 * roteamento (Spec 91 intacta): apenas expõe a ordem de fallback da policy do
 * lojista, o estado do circuito e o extrato por adquirente, além do catálogo
 * de integrações disponíveis.
 *
 * <p>Escopo por perfil (Spec 139): a ordem/fallback reflete o {@code
 * X-Profile-ID} ativo via {@link RoutingPolicyResolver#resolveProviders(UUID,
 * UUID)}; perfil {@code BOT_ONLY} nunca aplica override (rota por risco — a
 * defesa vive no resolver). O catálogo é global.</p>
 */
@Service
public class PortalGatewayService {

    /** Nome amigável por provider (Spec 107 D2). */
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "pagarme", "Pagar.me",
            "asaas", "Asaas",
            "mock", "Simulador",
            "suitpay", "Suitpay");

    /** Catálogo estático: adapters reais da Spec 91 (PIX) + placeholders. */
    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry("pagarme", "Pagar.me", "PIX", true),
            new CatalogEntry("asaas", "Asaas", "PIX", true),
            new CatalogEntry("mock", "Simulador", "PIX", true),
            new CatalogEntry("cielo", "Cielo", "CARTÃO", false),
            new CatalogEntry("bitpay", "BitPay", "CRIPTO", false));

    private final RoutingPolicyResolver routingPolicyResolver;
    private final PixProviderHealthRegistry healthRegistry;
    private final TransactionRepository transactionRepository;
    private final ProviderCatalog providerCatalog;

    public PortalGatewayService(RoutingPolicyResolver routingPolicyResolver,
                                PixProviderHealthRegistry healthRegistry,
                                TransactionRepository transactionRepository,
                                ProviderCatalog providerCatalog) {
        this.routingPolicyResolver = routingPolicyResolver;
        this.healthRegistry = healthRegistry;
        this.transactionRepository = transactionRepository;
        this.providerCatalog = providerCatalog;
    }

    /**
     * Visão dos adquirentes do lojista na ordem de fallback da policy (Spec
     * 107): providers fora da policy não são listados (KISS). Sem providers →
     * lista vazia (sem erro).
     */
    public GatewayOverviewResponse overview(UUID merchantId, UUID profileId) {
        List<String> providers = routingPolicyResolver.resolveProviders(merchantId, profileId);
        if (providers.isEmpty()) {
            return new GatewayOverviewResponse(List.of());
        }
        Instant[] month = monthWindow();
        Map<String, ProviderVolume> volumes = transactionRepository
                .sumAndCountApprovedByProvider(merchantId, month[0], month[1])
                .stream()
                .collect(Collectors.toMap(ProviderVolume::provider, Function.identity()));
        List<GatewayProviderResponse> items = new ArrayList<>(providers.size());
        for (int i = 0; i < providers.size(); i++) {
            String provider = providers.get(i);
            items.add(GatewayProviderResponse.from(
                    provider,
                    displayName(provider),
                    i + 1,
                    healthRegistry.stateName(provider),
                    volumes.get(provider)));
        }
        return new GatewayOverviewResponse(items);
    }

    /** Catálogo de integrações disponíveis (read-only; ativação = evolução). */
    public List<GatewayCatalogItemResponse> catalog() {
        Set<String> configured = configuredProviders();
        return CATALOG.stream()
                .map(e -> new GatewayCatalogItemResponse(
                        e.id(), e.name(), e.category(), e.available(), configured.contains(e.id())))
                .toList();
    }

    /** Nome amigável do provider (fallback: o próprio id). */
    private static String displayName(String provider) {
        return DISPLAY_NAMES.getOrDefault(provider, provider);
    }

    /** Providers presentes no roteamento configurado (Spec 91), sem PADRAO. */
    private Set<String> configuredProviders() {
        return providerCatalog.availableRoutes().stream()
                .filter(r -> !"PADRAO".equalsIgnoreCase(r))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Janela padrão do extrato: mês corrente (UTC) — padrão do dashboard. */
    private static Instant[] monthWindow() {
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new Instant[]{start, now};
    }

    /** Entrada estática do catálogo (Spec 107). */
    private record CatalogEntry(String id, String name, String category, boolean available) {
    }
}
