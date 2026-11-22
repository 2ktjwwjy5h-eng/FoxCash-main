package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.exception.TrackingProfileNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TrackingProfileBotRepository;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.domain.port.out.TrackingProviderClient;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingProfileRequest;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingSyncResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aplicação: CRUD de perfis de tracking do portal (Spec 81/D7) — escopado por
 * {@code merchantId} (404 cross-lojista), tokens nunca em resposta (máscara),
 * auditoria (Spec 41) na mesma transação. Vínculo de bots validado contra o
 * próprio lojista.
 */
@Service
public class TrackingProfileCommandService {

    private final TrackingProfileRepository profileRepository;
    private final TrackingProfileBotRepository botLinkRepository;
    private final StoreBotRepository botRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Map<TrackingServiceType, TrackingProviderClient> clients;
    private final Clock clock;

    public TrackingProfileCommandService(TrackingProfileRepository profileRepository,
                                         TrackingProfileBotRepository botLinkRepository,
                                         StoreBotRepository botRepository,
                                         PlatformActivityRecorder activityRecorder,
                                         List<TrackingProviderClient> providerClients,
                                         Clock clock) {
        this.profileRepository = profileRepository;
        this.botLinkRepository = botLinkRepository;
        this.botRepository = botRepository;
        this.activityRecorder = activityRecorder;
        this.clients = providerClients.stream()
                .collect(Collectors.toUnmodifiableMap(TrackingProviderClient::type, Function.identity()));
        this.clock = clock;
    }

    @Transactional
    public TrackingProfile create(UUID merchantId, TrackingProfileRequest request) {
        TrackingProfile profile = TrackingProfile.create(merchantId, request.name(),
                trim(request.facebookPixelId()), request.facebookAccessToken(),
                trim(request.tiktokPixelId()), request.tiktokAccessToken(),
                request.utmifyToken(), request.otimizeyToken(), clock.instant());
        TrackingProfile saved = profileRepository.save(profile);
        replaceBotLinks(merchantId, saved.id(), request.botIds());
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_CREATED, "TRACKING_PROFILE", saved.id(),
                merchantId, Map.of("name", saved.name()));
        return saved;
    }

    @Transactional
    public TrackingProfile update(UUID merchantId, UUID id, TrackingProfileRequest request) {
        TrackingProfile profile = requireProfile(merchantId, id);
        // Provedores de pixel: pixel null limpa o provedor INTEIRO (token junto —
        // achado do audit: antes, pixel null + token mantido → 400; não dava para
        // desconfigurar). Token null/blank com pixel presente = mantém o existente
        // (os tokens nunca são re-lidos da API — padrão das credenciais da Spec 35).
        String facebookPixel = trim(request.facebookPixelId());
        String facebookToken = facebookPixel == null ? null
                : keepIfBlank(profile.facebookAccessToken(), request.facebookAccessToken());
        String tiktokPixel = trim(request.tiktokPixelId());
        String tiktokToken = tiktokPixel == null ? null
                : keepIfBlank(profile.tiktokAccessToken(), request.tiktokAccessToken());
        profile.update(request.name(),
                facebookPixel, facebookToken,
                tiktokPixel, tiktokToken,
                keepIfBlank(profile.utmifyToken(), request.utmifyToken()),
                keepIfBlank(profile.otimizeyToken(), request.otimizeyToken()),
                clock.instant());
        TrackingProfile saved = profileRepository.save(profile);
        replaceBotLinks(merchantId, id, request.botIds());
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_UPDATED, "TRACKING_PROFILE", saved.id(),
                merchantId, Map.of("name", saved.name()));
        return saved;
    }

    @Transactional
    public TrackingProfile activate(UUID merchantId, UUID id) {
        TrackingProfile profile = requireProfile(merchantId, id);
        profile.activate(clock.instant());
        TrackingProfile saved = profileRepository.save(profile);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_ACTIVATED, "TRACKING_PROFILE", saved.id(),
                merchantId, Map.of("name", saved.name()));
        return saved;
    }

    @Transactional
    public TrackingProfile deactivate(UUID merchantId, UUID id) {
        TrackingProfile profile = requireProfile(merchantId, id);
        profile.deactivate(clock.instant());
        TrackingProfile saved = profileRepository.save(profile);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_DEACTIVATED, "TRACKING_PROFILE", saved.id(),
                merchantId, Map.of("name", saved.name()));
        return saved;
    }

    @Transactional
    public TrackingProfile duplicate(UUID merchantId, UUID id) {
        TrackingProfile source = requireProfile(merchantId, id);
        TrackingProfile copy = source.duplicate(merchantId,
                source.name() + " (copia)", clock.instant());
        TrackingProfile saved = profileRepository.save(copy);
        // Copia também os vínculos de bots (mesmos bots).
        replaceBotLinks(merchantId, saved.id(), botLinkRepository.findBotIdsByProfileId(id));
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_DUPLICATED, "TRACKING_PROFILE", saved.id(),
                merchantId, Map.of("from", id.toString(), "name", saved.name()));
        return saved;
    }

    @Transactional
    public void remove(UUID merchantId, UUID id) {
        TrackingProfile profile = requireProfile(merchantId, id);
        botLinkRepository.deleteByProfileId(id);
        profileRepository.delete(profile);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.TRACKING_PROFILE_DELETED, "TRACKING_PROFILE", id,
                merchantId, Map.of("name", profile.name()));
    }

    /** Teste de conexão por provedor configurado (Spec 81/D8) — falha de um não impede os demais. */
    @Transactional(readOnly = true)
    public List<TrackingSyncResult> sync(UUID merchantId, UUID id) {
        TrackingProfile profile = requireProfile(merchantId, id);
        List<TrackingSyncResult> results = new ArrayList<>();
        if (profile.isFacebookConfigured()) {
            results.add(syncOne("facebook", TrackingServiceType.FACEBOOK,
                    new TrackingProviderClient.ProviderCredentials(profile.facebookPixelId(),
                            profile.facebookAccessToken())));
        }
        if (profile.isTikTokConfigured()) {
            results.add(syncOne("tiktok", TrackingServiceType.TIKTOK,
                    new TrackingProviderClient.ProviderCredentials(profile.tiktokPixelId(),
                            profile.tiktokAccessToken())));
        }
        if (profile.isUtmifyConfigured()) {
            results.add(syncOne("utmify", TrackingServiceType.UTMIFY,
                    new TrackingProviderClient.ProviderCredentials(null, profile.utmifyToken())));
        }
        if (profile.isOtimizeyConfigured()) {
            results.add(syncOne("otimizey", TrackingServiceType.OTIMIZEY,
                    new TrackingProviderClient.ProviderCredentials(null, profile.otimizeyToken())));
        }
        return results;
    }

    private TrackingSyncResult syncOne(String service, TrackingServiceType type,
                                       TrackingProviderClient.ProviderCredentials credentials) {
        TrackingProviderClient client = clients.get(type);
        if (client == null) {
            return new TrackingSyncResult(service, false, "provedor nao registrado");
        }
        try {
            ProviderResult result = client.testConnection(credentials);
            return new TrackingSyncResult(service, result.ok(), result.message());
        } catch (Exception e) {
            return new TrackingSyncResult(service, false, e.getMessage());
        }
    }

    private void replaceBotLinks(UUID merchantId, UUID profileId, Collection<UUID> botIds) {
        for (UUID botId : botIds) {
            StoreBot bot = botRepository.findByIdAndMerchantId(botId, merchantId)
                    .orElseThrow(StoreBotNotFoundException::new);
            if (bot.isDeleted()) {
                throw new StoreBotNotFoundException();
            }
        }
        botLinkRepository.replaceBotLinks(profileId, botIds);
    }

    private TrackingProfile requireProfile(UUID merchantId, UUID id) {
        return profileRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(TrackingProfileNotFoundException::new);
    }

    /** Token do request vazio → mantém o existente (tokens nunca são re-lidos da API). */
    private static String keepIfBlank(String existing, String request) {
        return request == null || request.isBlank() ? existing : request;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
