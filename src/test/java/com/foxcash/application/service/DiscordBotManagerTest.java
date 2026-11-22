package com.foxcash.application.service;

import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do gerenciador de conexão Discord (Spec 74/T3.4): conecta validando o
 * token e registrando comandos slash (gateway ON), registra sem chamada externa
 * com o kill-switch OFF (dev/test), disconnect idempotente e estado em memória.
 */
class DiscordBotManagerTest {

    private static final UUID BOT_ID = UUID.randomUUID();

    private final DiscordBotApi discordApi = mock(DiscordBotApi.class);
    private final BotProperties properties = new BotProperties();
    private final BotLeadService botLeadService = mock(BotLeadService.class);

    private DiscordBotManager manager;

    @BeforeEach
    void setUp() {
        manager = new DiscordBotManager(discordApi, properties, botLeadService);
    }

    @Test
    void connectWithGatewayOnValidatesTokenAndRegistersCommands() {
        properties.setDiscordGatewayEnabled(true);
        when(discordApi.getMe("d-token"))
                .thenReturn(new DiscordBotApi.DiscordUserInfo(1L, "meubot", "Meu Bot"));

        DiscordBotApi.DiscordUserInfo info = manager.connect(BOT_ID, "d-token", "app-123");

        assertThat(info.username()).isEqualTo("meubot");
        assertThat(manager.isConnected(BOT_ID)).isTrue();
        verify(discordApi).getMe("d-token");
        verify(discordApi).putCommands(eq("app-123"), eq("d-token"), any());
    }

    @Test
    void connectWithGatewayOffSkipsExternalCalls() {
        // kill-switch default OFF (dev/test): registro sem validação externa.
        DiscordBotApi.DiscordUserInfo info = manager.connect(BOT_ID, "d-token", "app-123");

        assertThat(info).isNull();
        assertThat(manager.isConnected(BOT_ID)).isTrue();
        verify(discordApi, never()).getMe(any());
        verify(discordApi, never()).putCommands(any(), any(), any());
    }

    @Test
    void disconnectRemovesFromMemory() {
        manager.connect(BOT_ID, "d-token", "app-123");

        manager.disconnect(BOT_ID);

        assertThat(manager.isConnected(BOT_ID)).isFalse();
        assertThat(manager.connectedCount()).isZero();
    }

    // ---- Spec 78 (wizard): validate/applySetup stateless ----

    @Test
    void validateCallsGetMeWithoutConnecting() {
        when(discordApi.getMe("d-token"))
                .thenReturn(new DiscordBotApi.DiscordUserInfo(1L, "meubot", "Meu Bot"));

        DiscordBotApi.DiscordUserInfo info = manager.validate("d-token");

        assertThat(info.username()).isEqualTo("meubot");
        verify(discordApi).getMe("d-token");
        // Não conecta (não entra no `connected`) e não grava nada.
        assertThat(manager.isConnected(BOT_ID)).isFalse();
        verify(discordApi, never()).putCommands(any(), any(), any());
    }

    @Test
    void applySetupUpdatesProfileAndRegistersCommands() {
        byte[] avatar = new byte[]{1, 2, 3};

        manager.applySetup("d-token", "app-123", "Bot de vendas", avatar);

        verify(discordApi).updateProfile("d-token", "Bot de vendas", avatar);
        verify(discordApi).putCommands(eq("app-123"), eq("d-token"), any());
    }

    @Test
    void applySetupWithoutApplicationIdSkipsCommands() {
        manager.applySetup("d-token", null, "Descricao", null);

        verify(discordApi).updateProfile("d-token", "Descricao", null);
        verify(discordApi, never()).putCommands(any(), any(), any());
    }

    @Test
    void disconnectIsIdempotent() {
        manager.disconnect(BOT_ID);
        manager.disconnect(BOT_ID);

        assertThat(manager.connectedCount()).isZero();
    }

    @Test
    void connectedCountTracksActiveConnections() {
        manager.connect(BOT_ID, "d-token", "app-123");
        manager.connect(UUID.randomUUID(), "other", "app-456");

        assertThat(manager.connectedCount()).isEqualTo(2);
    }
}
