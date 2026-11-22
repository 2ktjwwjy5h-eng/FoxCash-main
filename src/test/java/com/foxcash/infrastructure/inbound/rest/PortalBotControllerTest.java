package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BroadcastChatService;
import com.foxcash.domain.exception.InvalidBotStateException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BotSetupRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotResponse;
import com.foxcash.infrastructure.inbound.rest.dto.StoreBotUpdateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ValidateBotTokenRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ValidateBotTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do registro de bots no portal (Spec 74/T4.4): o
 * merchantId vem do principal (ROLE_MERCHANT_PORTAL), POST registra (201), PUT
 * edita, pause/resume, DELETE 204 e os erros de domínio propagam (404
 * cross-lojista, 409 invalid_bot_state).
 */
class PortalBotControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private final StoreBotManagementUseCase bots = mock(StoreBotManagementUseCase.class);
    private final BroadcastChatService chatService = mock(BroadcastChatService.class);
    private final ProfileScopeResolver scopeResolver = mock(ProfileScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalBotController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalBotController(bots, chatService, scopeResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private StoreBot bot(BotStatus status) {
        return StoreBot.restore(UUID.randomUUID(), MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                status, null, NOW, null, NOW, NOW);
    }

    @Test
    void listReturnsBotsOfPrincipal() {
        // Sem header → frota inteira (scopeProfileId empty → profileId null).
        when(bots.list(MERCHANT_ID, null, null, null))
                .thenReturn(List.of(bot(BotStatus.ACTIVE)));

        List<StoreBotResponse> response =
                controller.list(authentication, new MockHttpServletRequest(), null, null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).tokenMask()).isEqualTo("****oken"); // nunca o token completo
        assertThat(response.get(0).tokenMask()).doesNotContain("123456");
    }

    @Test
    void getReturnsBotOfPrincipal() {
        StoreBot b = bot(BotStatus.ACTIVE);
        when(bots.get(MERCHANT_ID, b.id())).thenReturn(b);

        StoreBotResponse response = controller.get(authentication, b.id());

        assertThat(response.id()).isEqualTo(b.id());
    }

    @Test
    void getWithoutBotPropagates404() {
        UUID botId = UUID.randomUUID();
        when(bots.get(MERCHANT_ID, botId)).thenThrow(new StoreBotNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication, botId))
                .isInstanceOf(StoreBotNotFoundException.class);
    }

    @Test
    void registerReturns201WithBody() {
        when(bots.register(any(), any())).thenAnswer(inv -> {
            StoreBotManagementUseCase.RegisterBotCommand cmd = inv.getArgument(1);
            return bot(BotStatus.ACTIVE);
        });

        ResponseEntity<StoreBotResponse> response = controller.register(authentication,
                new StoreBotRequest("telegram", "123456:ABC-secret-token", "Bot Vendas", null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().platform()).isEqualTo(BotPlatform.TELEGRAM);
        verify(bots).register(any(), any());
    }

    @Test
    void registerRejectsInvalidPlatform() {
        assertThatThrownBy(() -> controller.register(authentication,
                new StoreBotRequest("whatsapp", "tok", "Bot", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateReturnsBody() {
        StoreBot b = bot(BotStatus.ACTIVE);
        when(bots.update(MERCHANT_ID, b.id(), "Novo Nome")).thenReturn(b);

        StoreBotResponse response = controller.update(authentication, b.id(),
                new StoreBotUpdateRequest("Novo Nome"));

        assertThat(response.id()).isEqualTo(b.id());
        verify(bots).update(MERCHANT_ID, b.id(), "Novo Nome");
    }

    @Test
    void pauseReturnsBody() {
        StoreBot b = bot(BotStatus.PAUSED);
        when(bots.pause(MERCHANT_ID, b.id())).thenReturn(b);

        StoreBotResponse response = controller.pause(authentication, b.id());

        assertThat(response.status()).isEqualTo(BotStatus.PAUSED);
    }

    @Test
    void pauseInvalidTransitionPropagates409() {
        StoreBot b = bot(BotStatus.REGISTERING);
        when(bots.pause(MERCHANT_ID, b.id())).thenThrow(new InvalidBotStateException("x"));

        assertThatThrownBy(() -> controller.pause(authentication, b.id()))
                .isInstanceOf(InvalidBotStateException.class);
    }

    @Test
    void resumeReturnsBody() {
        StoreBot b = bot(BotStatus.ACTIVE);
        when(bots.resume(MERCHANT_ID, b.id())).thenReturn(b);

        StoreBotResponse response = controller.resume(authentication, b.id());

        assertThat(response.status()).isEqualTo(BotStatus.ACTIVE);
    }

    @Test
    void removeReturns204() {
        UUID botId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.remove(authentication, botId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(bots).remove(MERCHANT_ID, botId);
    }

    @Test
    void validateReturnsIdentityWithoutCreating() {
        when(bots.validateToken("123456:ABC-secret-token"))
                .thenReturn(new com.foxcash.domain.port.out.TelegramBotApi.TelegramUserInfo(123, "vendasbot", "Bot Vendas"));

        ValidateBotTokenResponse response = controller.validate(authentication,
                new ValidateBotTokenRequest("123456:ABC-secret-token"));

        assertThat(response.displayName()).isEqualTo("Bot Vendas");
        assertThat(response.username()).isEqualTo("vendasbot");
        verify(bots).validateToken("123456:ABC-secret-token");
        verify(bots, never()).register(any(), any());
    }

    @Test
    void setupAppliesOptionalFields() {
        String base64Photo = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        controller.setup(authentication,
                new BotSetupRequest("123456:ABC-secret-token", "Bot de vendas", "Venda por aqui", base64Photo));

        verify(bots).applyTelegramSetup(argThat(cmd ->
                cmd.token().equals("123456:ABC-secret-token")
                        && cmd.description().equals("Bot de vendas")
                        && cmd.shortDescription().equals("Venda por aqui")
                        && java.util.Arrays.equals(cmd.photo(), new byte[]{1, 2, 3})));
    }

    @Test
    void setupWithoutPhotoPassesNullPhoto() {
        controller.setup(authentication,
                new BotSetupRequest("123456:ABC-secret-token", "Desc", null, null));

        verify(bots).applyTelegramSetup(argThat(cmd -> cmd.photo() == null && cmd.shortDescription() == null));
    }

    @Test
    void setupRejectsInvalidBase64Photo() {
        assertThatThrownBy(() -> controller.setup(authentication,
                new BotSetupRequest("123456:ABC-secret-token", "Desc", null, "!!!not-base64!!!")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Spec 78 (wizard): validate/setup Discord ----

    @Test
    void validateDiscordReturnsIdentityWithoutCreating() {
        when(bots.validateDiscordToken("d-token-secreto"))
                .thenReturn(new com.foxcash.domain.port.out.DiscordBotApi.DiscordUserInfo(123, "meubot", "Meu Bot"));

        ValidateBotTokenResponse response = controller.validate(authentication,
                new ValidateBotTokenRequest("d-token-secreto", "DISCORD"));

        assertThat(response.displayName()).isEqualTo("Meu Bot");
        assertThat(response.username()).isEqualTo("meubot");
        verify(bots).validateDiscordToken("d-token-secreto");
        verify(bots, never()).validateToken(any());
        verify(bots, never()).register(any(), any());
    }

    @Test
    void validateWithoutPlatformDefaultsToTelegram() {
        when(bots.validateToken("123456:ABC-secret-token"))
                .thenReturn(new com.foxcash.domain.port.out.TelegramBotApi.TelegramUserInfo(123, "vendasbot", "Bot Vendas"));

        controller.validate(authentication, new ValidateBotTokenRequest("123456:ABC-secret-token", null));

        verify(bots).validateToken("123456:ABC-secret-token");
        verify(bots, never()).validateDiscordToken(any());
    }

    @Test
    void setupDiscordAppliesAvatarDescriptionAndCommands() {
        String base64Avatar = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        controller.setup(authentication,
                new BotSetupRequest("d-token-secreto", "DISCORD", "app-123",
                        "Bot de vendas", null, null, base64Avatar));

        verify(bots).applyDiscordSetup(argThat(cmd ->
                cmd.token().equals("d-token-secreto")
                        && cmd.applicationId().equals("app-123")
                        && cmd.description().equals("Bot de vendas")
                        && java.util.Arrays.equals(cmd.avatar(), new byte[]{1, 2, 3})));
        verify(bots, never()).applyTelegramSetup(any());
    }

    @Test
    void setupDiscordWithoutAvatarPassesNull() {
        controller.setup(authentication,
                new BotSetupRequest("d-token-secreto", "DISCORD", "app-123", null, null, null, null));

        verify(bots).applyDiscordSetup(argThat(cmd -> cmd.avatar() == null));
    }

    @Test
    void setupDiscordRejectsInvalidBase64Avatar() {
        assertThatThrownBy(() -> controller.setup(authentication,
                new BotSetupRequest("d-token-secreto", "DISCORD", "app-123", "Desc", null, null, "!!!bad!!!")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
