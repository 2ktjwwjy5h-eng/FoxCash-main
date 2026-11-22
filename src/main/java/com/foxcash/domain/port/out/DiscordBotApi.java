package com.foxcash.domain.port.out;

import java.util.List;

/**
 * Porta de saída: API REST do Discord v10 (Spec 74) — validação do token do bot
 * ({@code GET /users/@me}) e registro de comandos slash
 * ({@code PUT /applications/{id}/commands}). A conexão de gateway em si vive no
 * renderizador (Spec 52, discord.js — decisão stateless); o backend registra e
 * acompanha o estado da conexão via este contrato REST.
 */
public interface DiscordBotApi {

    /** Valida o token (Bearer {@code Bot <token>}) e devolve a identidade do bot. */
    DiscordUserInfo getMe(String token);

    /** Registra os comandos slash do bot (idempotente). */
    void putCommands(String applicationId, String token, List<SlashCommand> commands);

    /**
     * Spec 78 (wizard): atualiza o perfil do bot via {@code PATCH /users/@me} —
     * descrição ("Sobre mim") e/ou avatar (bytes, enviado como data URI base64).
     * Campos ausentes/nulos são pulados; falha → {@code DiscordApiException}.
     */
    void updateProfile(String token, String description, byte[] avatar);

    /**
     * Guilds/canais do bot (Spec 79/D5 — destino dinâmico do broadcast):
     * {@code GET /users/@me/guilds} devolve as guilds onde o bot está. O
     * broadcast usa guilds/canais onde o bot tem permissão.
     */
    List<ChatInfo> listGuilds(String token);

    /** Identidade do bot retornada por {@code GET /users/@me}. */
    record DiscordUserInfo(long id, String username, String displayName) {
    }

    /** Guild do bot (destino do broadcast). */
    record ChatInfo(String id, String title) {
    }

    /** Comando slash a registrar. */
    record SlashCommand(String name, String description) {
    }
}
