package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request de configuração inicial opcional do bot (Specs 77/78 — wizard):
 * <ul>
 *   <li><b>Telegram</b> (default, Spec 77): foto de perfil (base64) e descrições
 *       via Bot API ({@code setMyPhoto}/{@code setMyDescription}/
 *       {@code setMyShortDescription}).</li>
 *   <li><b>Discord</b> (Spec 78): avatar (base64) e descrição via
 *       {@code PATCH /users/@me} + comandos slash via
 *       {@code PUT /applications/{id}/commands} ({@code applicationId}).</li>
 * </ul>
 * SEM gravar nada no FoxCash. Campos todos opcionais.
 */
public record BotSetupRequest(String token, String platform, String applicationId,
                              String description, String shortDescription,
                              String photoBase64, String avatarBase64) {

    /** Construtor retrocompatível da Spec 77 (Telegram, sem platform/avatar). */
    public BotSetupRequest(String token, String description, String shortDescription,
                           String photoBase64) {
        this(token, "TELEGRAM", null, description, shortDescription, photoBase64, null);
    }

    public boolean isDiscord() {
        return platform != null && platform.trim().equalsIgnoreCase("DISCORD");
    }
}
