package com.foxcash.application.service;

import com.foxcash.domain.exception.BotsNotAllowedForScopeException;
import com.foxcash.domain.exception.InvalidProfileScopeException;
import com.foxcash.domain.exception.OperationProfileNotFoundException;
import com.foxcash.domain.exception.RouteNotAllowedForScopeException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.exception.UnknownGatewayRouteException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.inbound.rest.dto.ProfileBotResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ProfilesResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: perfis de operação do portal (Spec 122 — fase 1, fundação do
 * seletor de contexto; Spec 139 — escopo GENERAL/BOT_ONLY/GATEWAY_ONLY + frota
 * por endpoint). Tudo por {@code merchantId} do principal — perfil de outro
 * lojista → 404 {@code profile_not_found} (padrão IDOR das Specs 24/31).
 *
 * <p>O perfil carrega a rota de gateway (cobrança Pix — PADRAO = null), o escopo
 * (Spec 139 — classificação da operação) e a frota de bots
 * ({@code store_bots.profile_id}). A rota é validada no catálogo configurado
 * (400 {@code unknown_gateway_route}); o header {@code X-Profile-ID} apenas
 * seleciona o perfil ativo — **nunca decide rota** (fonte da verdade é o banco).
 * Auditoria Spec 41 na mesma transação ({@code PROFILE_*}/GATEWAY_ROUTE_CHANGED/
 * PROFILE_BOTS_CHANGED, sem PII).</p>
 */
@Service
public class OperationProfileService {

    private static final String DEFAULT_PROFILE_NAME = "Padrão BR";
    private static final String PADRAO = "PADRAO";

    private final MerchantProfileRepository profileRepository;
    private final ProviderCatalog providerCatalog;
    private final StoreBotManagementUseCase bots;
    private final StoreBotRepository botRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public OperationProfileService(MerchantProfileRepository profileRepository,
                                   ProviderCatalog providerCatalog,
                                   StoreBotManagementUseCase bots,
                                   StoreBotRepository botRepository,
                                   PlatformActivityRecorder activityRecorder,
                                   Clock clock) {
        this.profileRepository = profileRepository;
        this.providerCatalog = providerCatalog;
        this.bots = bots;
        this.botRepository = botRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    /**
     * Perfis do lojista + perfil ativo. Cria o default ("Padrão BR") de forma
     * lazy quando o lojista não tem nenhum perfil (defesa — cobre dados legados
     * anteriores à V69 e lojistas criados fora do fluxo de registro).
     */
    @Transactional
    public ProfilesResponse get(UUID merchantId, UUID profileIdFromHeader) {
        List<MerchantProfile> profiles = ensureDefaultProfile(merchantId);
        UUID activeProfileId = resolveActiveProfile(profiles, profileIdFromHeader);
        UUID defaultProfileId = profiles.stream().filter(MerchantProfile::isDefault)
                .map(MerchantProfile::id).findFirst().orElse(null);
        List<StoreBot> allBots = bots.list(merchantId, null, null);
        List<String> routes = providerCatalog.availableRoutes();
        List<ProfileResponse> response = profiles.stream()
                .map(p -> toResponse(p, allBots, defaultProfileId, routes))
                .toList();
        return new ProfilesResponse(response, activeProfileId);
    }

    /**
     * Cria um perfil (o primeiro do lojista vira default). {@code scope} opcional
     * (Spec 139 — default GENERAL); {@code BOT_ONLY} não aceita rota (400
     * {@code route_not_allowed_for_scope}); escopo inválido → 400
     * {@code invalid_scope}.
     */
    @Transactional
    public ProfileResponse create(UUID merchantId, String name, String gatewayRoute, String scope) {
        ProfileScope resolvedScope = parseScope(scope);
        List<MerchantProfile> existing = profileRepository.findByMerchantId(merchantId);
        boolean isDefault = existing.isEmpty();
        int sortOrder = existing.stream().mapToInt(MerchantProfile::sortOrder).max().orElse(-1) + 1;
        Instant now = Instant.now(clock);
        MerchantProfile profile = MerchantProfile.create(merchantId, name, isDefault, sortOrder, now);
        if (resolvedScope == ProfileScope.BOT_ONLY) {
            if (gatewayRoute != null && !gatewayRoute.isBlank() && !PADRAO.equalsIgnoreCase(gatewayRoute)) {
                throw new RouteNotAllowedForScopeException(
                        "perfil BOT_ONLY nao aceita rota de gateway (roteamento por risco)");
            }
            // Rota permanece null (default do create) — roteamento por risco.
        } else {
            profile.changeGatewayRoute(normalizeRoute(gatewayRoute), now);
        }
        profile.changeScope(resolvedScope, now);
        MerchantProfile saved = profileRepository.save(profile);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.PROFILE_CREATED, "MERCHANT_PROFILE", saved.id(), merchantId,
                Map.of("name", saved.name(), "scope", saved.scope().name()));
        return toResponse(saved, List.of());
    }

    /**
     * Atualiza parcialmente um perfil (nome, rota e/ou escopo). {@code null} no
     * body = campo não alterado (padrão PUT parcial/Spec 48). Escrita sob lock
     * pessimista (SEC-19). Regras de escopo (Spec 139): {@code BOT_ONLY} força a
     * rota a null (rota enviada junto → 400 {@code route_not_allowed_for_scope});
     * {@code GATEWAY_ONLY} desanexa a frota na mesma transação.
     */
    @Transactional
    public ProfileResponse update(UUID merchantId, UUID profileId, String name, String gatewayRoute,
                                  String scope) {
        MerchantProfile profile = profileRepository.findByIdForUpdate(profileId)
                .filter(p -> p.merchantId().equals(merchantId))
                .orElseThrow(() -> new OperationProfileNotFoundException("profile_not_found"));
        Instant now = Instant.now(clock);
        String oldName = profile.name();
        String oldRoute = profile.gatewayRoute();
        ProfileScope oldScope = profile.scope();
        if (name != null) {
            profile.rename(name, now);
        }
        ProfileScope newScope = scope != null ? parseScope(scope) : null;
        if (newScope != null) {
            if (newScope == ProfileScope.BOT_ONLY) {
                if (gatewayRoute != null) {
                    throw new RouteNotAllowedForScopeException(
                            "perfil BOT_ONLY nao aceita rota de gateway (roteamento por risco)");
                }
                if (profile.gatewayRoute() != null) {
                    profile.changeGatewayRoute(null, now);
                }
            } else if (newScope == ProfileScope.GATEWAY_ONLY) {
                detachFleet(merchantId, profileId);
            }
            profile.changeScope(newScope, now);
        }
        if (gatewayRoute != null) {
            profile.changeGatewayRoute(normalizeRoute(gatewayRoute), now);
        }
        MerchantProfile saved = profileRepository.save(profile);
        if (!saved.name().equals(oldName)) {
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.PROFILE_UPDATED, "MERCHANT_PROFILE", saved.id(), merchantId,
                    Map.of("name", saved.name()));
        }
        if (!java.util.Objects.equals(oldRoute, saved.gatewayRoute())) {
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.GATEWAY_ROUTE_CHANGED, "MERCHANT_PROFILE", saved.id(), merchantId,
                    Map.of("gatewayRoute", routeLabel(saved.gatewayRoute())));
        }
        if (!java.util.Objects.equals(oldScope, saved.scope())) {
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.PROFILE_UPDATED, "MERCHANT_PROFILE", saved.id(), merchantId,
                    Map.of("name", saved.name(), "scope", saved.scope().name()));
        }
        return toResponse(saved, List.of());
    }

    /**
     * Substitui a frota de bots do perfil (Spec 139 — endpoint novo
     * {@code PUT /api/v1/portal/profile/{id}/bots}): {@code botIds} vazio esvazia
     * a frota; cada bot é validado (do lojista, não-deletado → 400
     * {@code bot_not_found}); perfil {@code GATEWAY_ONLY} → 400
     * {@code bots_not_allowed_for_scope}. Auditoria {@code PROFILE_BOTS_CHANGED}
     * com contagem antes/depois. Transação única (desanexa e anexa).
     */
    @Transactional
    public ProfileResponse replaceBots(UUID merchantId, UUID profileId, List<UUID> botIds) {
        MerchantProfile profile = profileRepository.findByIdForUpdate(profileId)
                .filter(p -> p.merchantId().equals(merchantId))
                .orElseThrow(() -> new OperationProfileNotFoundException("profile_not_found"));
        if (profile.scope() == ProfileScope.GATEWAY_ONLY) {
            throw new BotsNotAllowedForScopeException(
                    "perfil GATEWAY_ONLY nao aceita frota de bots");
        }
        List<UUID> ids = botIds == null ? List.of() : botIds.stream().distinct().toList();
        List<StoreBot> currentFleet = botRepository.findByMerchantId(merchantId).stream()
                .filter(b -> profileId.equals(b.profileId()))
                .toList();
        int before = currentFleet.size();
        // Valida cada bot antes de mutar (falha → nada muda).
        List<StoreBot> target = ids.stream()
                .map(id -> botRepository.findByIdAndMerchantId(id, merchantId)
                        .filter(b -> !b.isDeleted())
                        .orElseThrow(StoreBotNotFoundException::new))
                .toList();
        // Desanexa os que saíram da frota.
        for (StoreBot bot : currentFleet) {
            if (!ids.contains(bot.id())) {
                bot.assignProfile(null);
                botRepository.save(bot);
            }
        }
        // Anexa os novos.
        for (StoreBot bot : target) {
            if (!profileId.equals(bot.profileId())) {
                bot.assignProfile(profileId);
                botRepository.save(bot);
            }
        }
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.PROFILE_BOTS_CHANGED, "MERCHANT_PROFILE", profileId, merchantId,
                Map.of("beforeCount", before, "afterCount", ids.size()));
        UUID defaultProfileId = profileRepository.defaultProfileIdOf(merchantId).orElse(null);
        List<StoreBot> allBots = botRepository.findByMerchantId(merchantId);
        return toResponse(profile, allBots, defaultProfileId, providerCatalog.availableRoutes());
    }

    private List<MerchantProfile> ensureDefaultProfile(UUID merchantId) {
        List<MerchantProfile> profiles = profileRepository.findByMerchantId(merchantId);
        if (profiles.isEmpty()) {
            MerchantProfile created = MerchantProfile.create(merchantId, DEFAULT_PROFILE_NAME,
                    true, 0, Instant.now(clock));
            profiles = List.of(profileRepository.save(created));
        }
        return profiles;
    }

    /** Perfil do header se pertence ao lojista; senão o default (nunca vaza). */
    private UUID resolveActiveProfile(List<MerchantProfile> profiles, UUID fromHeader) {
        if (fromHeader != null && profiles.stream().anyMatch(p -> p.id().equals(fromHeader))) {
            return fromHeader;
        }
        return profiles.stream().filter(MerchantProfile::isDefault)
                .map(MerchantProfile::id).findFirst().orElse(null);
    }

    /** Normaliza e valida a rota no catálogo (PADRAO/blank → null; fora → 400). */
    private String normalizeRoute(String route) {
        if (route == null || route.isBlank() || PADRAO.equalsIgnoreCase(route)) {
            return null;
        }
        String normalized = route.trim().toLowerCase();
        if (!providerCatalog.contains(normalized)) {
            throw new UnknownGatewayRouteException("rota de gateway desconhecida: " + normalized);
        }
        return normalized;
    }

    /**
     * Escopo do request (Spec 139). null/blank → GENERAL (default); valor fora do
     * enum → 400 {@code invalid_scope}.
     */
    private ProfileScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return ProfileScope.GENERAL;
        }
        try {
            return ProfileScope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidProfileScopeException("escopo invalido: " + scope);
        }
    }

    /** GATEWAY_ONLY: desanexa a frota do perfil (bots voltam ao default — profile_id NULL). */
    private void detachFleet(UUID merchantId, UUID profileId) {
        List<StoreBot> fleet = botRepository.findByMerchantId(merchantId).stream()
                .filter(b -> profileId.equals(b.profileId()))
                .toList();
        for (StoreBot bot : fleet) {
            bot.assignProfile(null);
            botRepository.save(bot);
        }
    }

    /** Frota do perfil: bot com {@code profileId} do perfil ou, se NULL (legado), o default do lojista. */
    private ProfileResponse toResponse(MerchantProfile profile, List<StoreBot> allBots,
                                       UUID defaultProfileId, List<String> routes) {
        List<ProfileBotResponse> botsOfProfile = allBots.stream()
                .filter(b -> profile.id().equals(b.profileId() != null ? b.profileId() : defaultProfileId))
                .map(ProfileBotResponse::from)
                .toList();
        return new ProfileResponse(profile.id(), profile.name(), profile.scope().name(),
                routeLabel(profile.gatewayRoute()), routes, botsOfProfile);
    }

    /** Sem bots (create/update — a frota só aparece no GET). */
    private ProfileResponse toResponse(MerchantProfile profile, List<StoreBot> ignoredBots) {
        return new ProfileResponse(profile.id(), profile.name(), profile.scope().name(),
                routeLabel(profile.gatewayRoute()), providerCatalog.availableRoutes(), List.of());
    }

    /** null → "PADRAO" na resposta (exibição). */
    private static String routeLabel(String route) {
        return route == null ? PADRAO : route;
    }
}
