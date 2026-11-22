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
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.inbound.rest.dto.ProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ProfilesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de perfis de operação (Spec 122/T2.3 + T3 + Spec 139):
 * default lazy, header validado (perfil de outro lojista → default, nunca vaza),
 * criação (1º vira default), rota desconhecida → 400, IDOR no update (404),
 * escopo (create/update com BOT_ONLY/GATEWAY_ONLY) e substituição de frota.
 */
class OperationProfileServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PROFILE_A = UUID.randomUUID();
    private static final UUID PROFILE_B = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final ProviderCatalog providerCatalog = mock(ProviderCatalog.class);
    private final StoreBotManagementUseCase bots = mock(StoreBotManagementUseCase.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = mock(Clock.class);

    private OperationProfileService service;

    @BeforeEach
    void setUp() {
        service = new OperationProfileService(profileRepository, providerCatalog, bots,
                botRepository, activityRecorder, clock);
        when(clock.instant()).thenReturn(NOW);
        when(bots.list(MERCHANT_ID, null, null)).thenReturn(List.of());
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());
        when(providerCatalog.availableRoutes()).thenReturn(List.of("PADRAO", "pagarme"));
        // save retorna o próprio perfil (persistência simulada).
        when(profileRepository.save(any(MerchantProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(botRepository.save(any(StoreBot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private MerchantProfile profile(UUID id, String name, boolean isDefault, int sortOrder, String route) {
        return profile(id, name, isDefault, sortOrder, route, ProfileScope.GENERAL);
    }

    private MerchantProfile profile(UUID id, String name, boolean isDefault, int sortOrder, String route,
                                    ProfileScope scope) {
        return MerchantProfile.restore(id, MERCHANT_ID, name, route, scope, isDefault, sortOrder,
                null, NOW, NOW);
    }

    private StoreBot bot(UUID id, UUID profileId) {
        StoreBot bot = StoreBot.restore(id, MERCHANT_ID, BotPlatform.TELEGRAM, "tok", "hash", "wsh",
                "Bot " + id, null, null, BotStatus.ACTIVE, null, null, null, NOW, NOW);
        bot.assignProfile(profileId);
        return bot;
    }

    @Test
    void getSemPerfisCriaDefaultLazy() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

        ProfilesResponse response = service.get(MERCHANT_ID, null);

        assertThat(response.profiles()).hasSize(1);
        assertThat(response.profiles().get(0).name()).isEqualTo("Padrão BR");
        assertThat(response.profiles().get(0).gatewayRoute()).isEqualTo("PADRAO");
        assertThat(response.profiles().get(0).scope()).isEqualTo("GENERAL");
        assertThat(response.activeProfileId()).isEqualTo(response.profiles().get(0).id());
        verify(profileRepository).save(any(MerchantProfile.class));
    }

    @Test
    void getComHeaderDoProprioLojistaUsaAquelePerfil() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(
                profile(PROFILE_A, "Padrão BR", true, 0, null),
                profile(PROFILE_B, "Global", false, 1, null)));

        ProfilesResponse response = service.get(MERCHANT_ID, PROFILE_B);

        assertThat(response.activeProfileId()).isEqualTo(PROFILE_B);
    }

    @Test
    void getComHeaderDeOutroLojistaUsaDefault() {
        when(profileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(profile(PROFILE_A, "Padrão BR", true, 0, null)));

        // Perfil de OUTRO lojista no header → ignorado → default (nunca vaza).
        ProfilesResponse response = service.get(MERCHANT_ID, UUID.randomUUID());

        assertThat(response.activeProfileId()).isEqualTo(PROFILE_A);
    }

    @Test
    void createPrimeiroViraDefaultEAudita() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

        ProfileResponse response = service.create(MERCHANT_ID, "Global", "PADRAO", null);

        assertThat(response.name()).isEqualTo("Global");
        assertThat(response.gatewayRoute()).isEqualTo("PADRAO");
        assertThat(response.scope()).isEqualTo("GENERAL");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.PROFILE_CREATED), eq("MERCHANT_PROFILE"), any(UUID.class),
                eq(MERCHANT_ID), any(Map.class));
    }

    @Test
    void createComRotaDesconhecidaLanca400() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());
        when(providerCatalog.contains("stripe")).thenReturn(false);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, "Global", "stripe", null))
                .isInstanceOf(UnknownGatewayRouteException.class);
    }

    @Test
    void createComEscopoInvalidoLanca400() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(MERCHANT_ID, "Global", null, "TURBO"))
                .isInstanceOf(InvalidProfileScopeException.class);
    }

    @Test
    void createBotOnlyNaoAceitaRota() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(MERCHANT_ID, "Bot Padrão", "pagarme", "BOT_ONLY"))
                .isInstanceOf(RouteNotAllowedForScopeException.class);
    }

    @Test
    void createBotOnlyComPADRAOForcaRotaNull() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());

        ProfileResponse response = service.create(MERCHANT_ID, "Bot Padrão", "PADRAO", "BOT_ONLY");

        assertThat(response.scope()).isEqualTo("BOT_ONLY");
        assertThat(response.gatewayRoute()).isEqualTo("PADRAO");
    }

    @Test
    void createGatewayOnlyComRotaValidaPersisteScope() {
        when(profileRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());
        when(providerCatalog.contains("pagarme")).thenReturn(true);

        ProfileResponse response = service.create(MERCHANT_ID, "Loja BR", "pagarme", "GATEWAY_ONLY");

        assertThat(response.scope()).isEqualTo("GATEWAY_ONLY");
        assertThat(response.gatewayRoute()).isEqualTo("pagarme");
    }

    @Test
    void updateDeOutroLojistaLanca404() {
        when(profileRepository.findByIdForUpdate(PROFILE_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MERCHANT_ID, PROFILE_B, null, "pagarme", null))
                .isInstanceOf(OperationProfileNotFoundException.class);
    }

    @Test
    void updateRotaPersistidaAuditaGatewayRouteChanged() {
        when(providerCatalog.contains("pagarme")).thenReturn(true);
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, null)));
        when(profileRepository.save(any(MerchantProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileResponse response = service.update(MERCHANT_ID, PROFILE_A, null, "pagarme", null);

        assertThat(response.gatewayRoute()).isEqualTo("pagarme");
        verify(activityRecorder).record(PlatformActorType.MERCHANT, MERCHANT_ID,
                PlatformAction.GATEWAY_ROUTE_CHANGED, "MERCHANT_PROFILE", PROFILE_A, MERCHANT_ID,
                Map.of("gatewayRoute", "pagarme"));
    }

    @Test
    void updateParaBotOnlyForcaRotaNullSemEnviarRota() {
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, "pagarme")));

        ProfileResponse response = service.update(MERCHANT_ID, PROFILE_A, null, null, "BOT_ONLY");

        assertThat(response.scope()).isEqualTo("BOT_ONLY");
        assertThat(response.gatewayRoute()).isEqualTo("PADRAO");
    }

    @Test
    void updateParaBotOnlyComRotaEnviadaLanca400() {
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, null)));

        assertThatThrownBy(() -> service.update(MERCHANT_ID, PROFILE_A, null, "pagarme", "BOT_ONLY"))
                .isInstanceOf(RouteNotAllowedForScopeException.class);
    }

    @Test
    void updateParaGatewayOnlyDesanexaFrotaNaMesmaTransacao() {
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, "pagarme")));
        when(botRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(bot(botA, PROFILE_A), bot(botB, null)));

        ProfileResponse response = service.update(MERCHANT_ID, PROFILE_A, null, null, "GATEWAY_ONLY");

        assertThat(response.scope()).isEqualTo("GATEWAY_ONLY");
        // O bot que pertencia ao perfil foi desanexado (profile_id → null).
        verify(botRepository).save(argThat(b -> b.id().equals(botA) && b.profileId() == null));
    }

    @Test
    void updateComEscopoInvalidoLanca400() {
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, null)));

        assertThatThrownBy(() -> service.update(MERCHANT_ID, PROFILE_A, null, null, "TURBO"))
                .isInstanceOf(InvalidProfileScopeException.class);
    }

    // ---- Spec 139: replaceBots (PUT /profile/{id}/bots) ----

    @Test
    void replaceBotsSubstituiFrotaEAudita() {
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        StoreBot botOfProfile = bot(botA, PROFILE_B);
        StoreBot botOutsider = bot(botB, null);
        // PROFILE_B não é o default (PROFILE_A é) — a resposta não inclui os bots
        // desanexados (NULL volta ao default do lojista, Spec 122/124).
        when(profileRepository.findByIdForUpdate(PROFILE_B))
                .thenReturn(Optional.of(profile(PROFILE_B, "Global", false, 1, null)));
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(botOfProfile, botOutsider));
        when(botRepository.findByIdAndMerchantId(botB, MERCHANT_ID)).thenReturn(Optional.of(botOutsider));
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(PROFILE_A));

        ProfileResponse response = service.replaceBots(MERCHANT_ID, PROFILE_B, List.of(botB));

        assertThat(response.bots()).hasSize(1);
        assertThat(response.bots().get(0).id()).isEqualTo(botB);
        // botA saiu da frota (profile_id null → volta ao default); botB entrou.
        verify(botRepository).save(argThat(b -> b.id().equals(botA) && b.profileId() == null));
        verify(botRepository).save(argThat(b -> b.id().equals(botB) && PROFILE_B.equals(b.profileId())));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.PROFILE_BOTS_CHANGED), eq("MERCHANT_PROFILE"), eq(PROFILE_B),
                eq(MERCHANT_ID), eq(Map.of("beforeCount", 1, "afterCount", 1)));
    }

    @Test
    void replaceBotsComListaVaziaEsvaziaFrota() {
        UUID botA = UUID.randomUUID();
        StoreBot botOfProfile = bot(botA, PROFILE_B);
        when(profileRepository.findByIdForUpdate(PROFILE_B))
                .thenReturn(Optional.of(profile(PROFILE_B, "Global", false, 1, null)));
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(botOfProfile));
        when(profileRepository.defaultProfileIdOf(MERCHANT_ID)).thenReturn(Optional.of(PROFILE_A));

        ProfileResponse response = service.replaceBots(MERCHANT_ID, PROFILE_B, List.of());

        assertThat(response.bots()).isEmpty();
        verify(botRepository).save(argThat(b -> b.id().equals(botA) && b.profileId() == null));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.PROFILE_BOTS_CHANGED), eq("MERCHANT_PROFILE"), eq(PROFILE_B),
                eq(MERCHANT_ID), eq(Map.of("beforeCount", 1, "afterCount", 0)));
    }

    @Test
    void replaceBotsComBotDeOutroLojistaLanca404() {
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Padrão BR", true, 0, null)));
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());
        when(botRepository.findByIdAndMerchantId(any(UUID.class), eq(MERCHANT_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceBots(MERCHANT_ID, PROFILE_A,
                List.of(UUID.randomUUID()))).isInstanceOf(StoreBotNotFoundException.class);
    }

    @Test
    void replaceBotsEmPerfilGatewayOnlyLanca400() {
        when(profileRepository.findByIdForUpdate(PROFILE_A))
                .thenReturn(Optional.of(profile(PROFILE_A, "Loja BR", true, 0, "pagarme",
                        ProfileScope.GATEWAY_ONLY)));

        assertThatThrownBy(() -> service.replaceBots(MERCHANT_ID, PROFILE_A, List.of()))
                .isInstanceOf(BotsNotAllowedForScopeException.class);
    }

    @Test
    void replaceBotsDeOutroLojistaLanca404() {
        when(profileRepository.findByIdForUpdate(PROFILE_B)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replaceBots(MERCHANT_ID, PROFILE_B, List.of()))
                .isInstanceOf(OperationProfileNotFoundException.class);
    }
}
