package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidBotStateException;
import com.foxcash.domain.exception.StoreBotAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de registro de bots de venda (Spec 74): registro com
 * integração (sucesso → ACTIVE; falha → ERROR com auditoria), token duplicado
 * → 409, edição, pausar/retomar/remover (com auditoria) e 404 cross-lojista.
 */
class StoreBotCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final StoreBotIntegrationService integrationService = mock(StoreBotIntegrationService.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StoreBotCommandService service;

    @BeforeEach
    void setUp() {
        service = new StoreBotCommandService(botRepository, integrationService, activityRecorder, clock);
    }

    private StoreBotManagementUseCase.RegisterBotCommand telegramCommand() {
        return new StoreBotManagementUseCase.RegisterBotCommand(
                BotPlatform.TELEGRAM, "123456:ABC-secret-token", "Bot Vendas", null);
    }

    private StoreBot savedBot(BotStatus status) {
        return StoreBot.restore(UUID.randomUUID(), MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                status, null, NOW, null, NOW, NOW);
    }

    @Test
    void registerIntegratesAndActivates() {
        when(botRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreBot created = service.register(MERCHANT_ID, telegramCommand());

        assertThat(created.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(created.platform()).isEqualTo(BotPlatform.TELEGRAM);
        assertThat(created.tokenMask()).isEqualTo("****oken");
        verify(integrationService).integrate(any(StoreBot.class));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_REGISTERED), eq("STORE_BOT"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void registerWithDuplicateTokenThrows409() {
        when(botRepository.findByTokenHash(any())).thenReturn(Optional.of(savedBot(BotStatus.ACTIVE)));

        assertThatThrownBy(() -> service.register(MERCHANT_ID, telegramCommand()))
                .isInstanceOf(StoreBotAlreadyExistsException.class);

        verify(botRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerWithConcurrentReplayThrows409InsteadOf500() {
        // Replay concorrente (Spec 49): o check findByTokenHash viu o banco vazio,
        // mas outra requisição inseriu o MESMO token antes do nosso flush — a
        // UNIQUE de token_hash lança DataIntegrityViolationException no save e o
        // serviço converte em 409 bot_already_exists (nunca 500).
        when(botRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        when(botRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("UNIQUE token_hash"));

        assertThatThrownBy(() -> service.register(MERCHANT_ID, telegramCommand()))
                .isInstanceOf(StoreBotAlreadyExistsException.class);

        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
        verify(integrationService, never()).integrate(any());
    }

    @Test
    void registerWithIntegrationFailureMarksErrorAndAudits() {
        when(botRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("getMe falhou: 401")).when(integrationService).integrate(any());

        StoreBot created = service.register(MERCHANT_ID, telegramCommand());

        assertThat(created.status()).isEqualTo(BotStatus.ERROR);
        assertThat(created.errorMessage()).contains("getMe falhou");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_INTEGRATION_ERROR), eq("STORE_BOT"), any(), eq(MERCHANT_ID), any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_REGISTERED), eq("STORE_BOT"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void registerRejectsBlankTokenOrPlatform() {
        assertThatThrownBy(() -> service.register(MERCHANT_ID,
                new StoreBotManagementUseCase.RegisterBotCommand(null, "tok", "Bot", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(MERCHANT_ID,
                new StoreBotManagementUseCase.RegisterBotCommand(BotPlatform.TELEGRAM, "  ", "Bot", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listFiltersByMerchantPlatformAndStatus() {
        StoreBot active = savedBot(BotStatus.ACTIVE);
        StoreBot paused = StoreBot.restore(UUID.randomUUID(), MERCHANT_ID, BotPlatform.DISCORD,
                "d-token", "h2", "wh2", "Bot D", "meubot", "app-1", BotStatus.PAUSED, null, NOW, null, NOW, NOW);
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(active, paused));

        assertThat(service.list(MERCHANT_ID, null, null)).hasSize(2);
        assertThat(service.list(MERCHANT_ID, BotPlatform.TELEGRAM, null)).hasSize(1);
        assertThat(service.list(MERCHANT_ID, null, BotStatus.PAUSED)).hasSize(1);
        assertThat(service.list(MERCHANT_ID, BotPlatform.DISCORD, BotStatus.ACTIVE)).isEmpty();
    }

    @Test
    void getByOtherMerchantThrows404() {
        UUID botId = UUID.randomUUID();
        when(botRepository.findByIdAndMerchantId(botId, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID, botId))
                .isInstanceOf(StoreBotNotFoundException.class);
    }

    @Test
    void updateChangesDisplayNameAndAudits() {
        StoreBot bot = savedBot(BotStatus.ACTIVE);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreBot updated = service.update(MERCHANT_ID, bot.id(), "Novo Nome");

        assertThat(updated.displayName()).isEqualTo("Novo Nome");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_UPDATED), eq("STORE_BOT"), eq(bot.id()), eq(MERCHANT_ID), any());
    }

    @Test
    void pauseDisintegratesAndAudits() {
        StoreBot bot = savedBot(BotStatus.ACTIVE);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreBot paused = service.pause(MERCHANT_ID, bot.id());

        assertThat(paused.status()).isEqualTo(BotStatus.PAUSED);
        verify(integrationService).disintegrate(bot);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_PAUSED), eq("STORE_BOT"), eq(bot.id()), eq(MERCHANT_ID), any());
    }

    @Test
    void pauseFromNonActiveThrows409() {
        StoreBot bot = savedBot(BotStatus.REGISTERING);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));

        assertThatThrownBy(() -> service.pause(MERCHANT_ID, bot.id()))
                .isInstanceOf(InvalidBotStateException.class);
        verify(integrationService, never()).disintegrate(any());
    }

    @Test
    void resumeReintegratesAndAudits() {
        StoreBot bot = savedBot(BotStatus.PAUSED);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreBot resumed = service.resume(MERCHANT_ID, bot.id());

        assertThat(resumed.status()).isEqualTo(BotStatus.ACTIVE);
        verify(integrationService).integrate(bot);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_RESUMED), eq("STORE_BOT"), eq(bot.id()), eq(MERCHANT_ID), any());
    }

    @Test
    void resumeWithIntegrationFailureMarksError() {
        StoreBot bot = savedBot(BotStatus.PAUSED);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("setWebhook falhou")).when(integrationService).integrate(bot);

        StoreBot resumed = service.resume(MERCHANT_ID, bot.id());

        assertThat(resumed.status()).isEqualTo(BotStatus.ERROR);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_INTEGRATION_ERROR), eq("STORE_BOT"), eq(bot.id()), eq(MERCHANT_ID), any());
    }

    @Test
    void removeSoftDeletesAndAudits() {
        StoreBot bot = savedBot(BotStatus.ACTIVE);
        when(botRepository.findByIdAndMerchantId(bot.id(), MERCHANT_ID)).thenReturn(Optional.of(bot));
        when(botRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.remove(MERCHANT_ID, bot.id());

        assertThat(bot.isDeleted()).isTrue();
        verify(integrationService).disintegrate(bot);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BOT_REMOVED), eq("STORE_BOT"), eq(bot.id()), eq(MERCHANT_ID), any());
    }

    // ---- Spec 77 (wizard): validate/setup stateless ----

    @Test
    void validateTokenDelegatesToIntegrationWithoutCreating() {
        when(integrationService.validateToken("123456:ABC-secret-token"))
                .thenReturn(new com.foxcash.domain.port.out.TelegramBotApi.TelegramUserInfo(123, "vendasbot", "Bot Vendas"));

        var me = service.validateToken("123456:ABC-secret-token");

        assertThat(me.displayName()).isEqualTo("Bot Vendas");
        assertThat(me.username()).isEqualTo("vendasbot");
        verify(integrationService).validateToken("123456:ABC-secret-token");
        verify(botRepository, never()).save(any());
    }

    @Test
    void validateTokenRejectsBlank() {
        assertThatThrownBy(() -> service.validateToken("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyTelegramSetupDelegatesToIntegration() {
        byte[] photo = new byte[]{1, 2, 3};
        service.applyTelegramSetup(new StoreBotManagementUseCase.TelegramSetupCommand(
                "123456:ABC-secret-token", "Descricao", "Curta", photo, "bot-photo.jpg"));

        verify(integrationService).applyTelegramSetup(
                "123456:ABC-secret-token", "Descricao", "Curta", photo, "bot-photo.jpg");
        verify(botRepository, never()).save(any());
    }

    // ---- Spec 78 (wizard): validate/setup Discord stateless ----

    @Test
    void validateDiscordTokenDelegatesToIntegrationWithoutCreating() {
        when(integrationService.validateDiscordToken("d-token-secreto"))
                .thenReturn(new com.foxcash.domain.port.out.DiscordBotApi.DiscordUserInfo(123, "meubot", "Meu Bot"));

        var me = service.validateDiscordToken("d-token-secreto");

        assertThat(me.displayName()).isEqualTo("Meu Bot");
        assertThat(me.username()).isEqualTo("meubot");
        verify(integrationService).validateDiscordToken("d-token-secreto");
        verify(botRepository, never()).save(any());
    }

    @Test
    void validateDiscordTokenRejectsBlank() {
        assertThatThrownBy(() -> service.validateDiscordToken("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyDiscordSetupDelegatesToIntegration() {
        byte[] avatar = new byte[]{1, 2, 3};
        service.applyDiscordSetup(new StoreBotManagementUseCase.DiscordSetupCommand(
                "d-token-secreto", "app-123", "Bot de vendas", avatar));

        verify(integrationService).applyDiscordSetup(
                "d-token-secreto", "app-123", "Bot de vendas", avatar);
        verify(botRepository, never()).save(any());
    }

    @Test
    void listScopesFleetByProfileWhenProfilePresent() {
        // Spec 124 (frota do perfil): com profileId → store_bots.profile_id =
        // perfil (merchant-scoped — perfil de outro lojista → vazio, nunca vaza).
        UUID profileId = UUID.randomUUID();
        StoreBot bot1 = savedBot(BotStatus.ACTIVE);
        when(botRepository.findByMerchantIdAndProfileId(MERCHANT_ID, profileId))
                .thenReturn(List.of(bot1));

        List<StoreBot> result = service.list(MERCHANT_ID, profileId, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(bot1.id());
        verify(botRepository).findByMerchantIdAndProfileId(MERCHANT_ID, profileId);
    }

    @Test
    void listWithoutProfileIsWholeMerchantFleetRegression() {
        // Sem header (profileId null) → frota inteira do lojista (regressão).
        StoreBot bot1 = savedBot(BotStatus.ACTIVE);
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(bot1));

        List<StoreBot> result = service.list(MERCHANT_ID, null, null, null);

        assertThat(result).hasSize(1);
        verify(botRepository).findByMerchantId(MERCHANT_ID);
    }
}
