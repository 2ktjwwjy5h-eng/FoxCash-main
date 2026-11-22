package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request de pré-validação do token do bot (Specs 77/78 — wizard): o token é
 * validado via {@code getMe} SEM criar registro. Nunca é armazenado nem logado.
 * {@code platform} (default TELEGRAM — compatível com o wizard da Spec 77 que
 * não envia o campo) decide qual API consultar.
 */
public record ValidateBotTokenRequest(String token, String platform) {

    /** Construtor retrocompatível da Spec 77 (sem platform → TELEGRAM). */
    public ValidateBotTokenRequest(String token) {
        this(token, "TELEGRAM");
    }

    public boolean isDiscord() {
        return platform != null && platform.trim().equalsIgnoreCase("DISCORD");
    }
}
