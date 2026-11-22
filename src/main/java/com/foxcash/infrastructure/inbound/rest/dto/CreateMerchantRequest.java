package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de criacao de lojista (Spec 39) — cadastro com nome, e-mail e senha
 * (sem CPF/CNPJ). A senha é escolhida pelo usuário (8–72 caracteres); o e-mail
 * é o identificador único de login.
 *
 * <p>{@code turnstileToken} (Spec 63, Fase C): token do widget Turnstile do
 * Cloudflare — obrigatório apenas quando a validação anti-bot estiver ativa
 * (default off; ver {@code foxcash.security.turnstile.*}).
 *
 * <p>{@code fingerprintHash} (Spec 141): hash SHA-256 (64 hex) dos sinais do
 * navegador — sinal de risco PASSIVO (opcional; inválido/ausente nunca quebra
 * o cadastro).
 */
public record CreateMerchantRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        String turnstileToken,
        @Size(max = 64) String fingerprintHash) {

    /** Compat: requests sem token (feature Turnstile desligada por default). */
    public CreateMerchantRequest(String name, String email, String password) {
        this(name, email, password, null, null);
    }
}
