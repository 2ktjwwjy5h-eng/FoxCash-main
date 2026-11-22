package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de login do lojista no portal (Spec 20; identificador por e-mail desde
 * a Spec 39). {@code email} é o identificador único do lojista na plataforma.
 *
 * <p>{@code totpCode} é opcional (Spec 94): exigido apenas quando o lojista tem
 * MFA ativo (código TOTP de 6 dígitos ou recovery code {@code mrc_...}). Sem MFA
 * ativo, o campo é ignorado — zero regressão no login atual.
 *
 * <p>{@code turnstileToken} (Spec 63, Fase C): token do widget Turnstile do
 * Cloudflare — obrigatório apenas quando a validação anti-bot estiver ativa
 * (default off; ver {@code foxcash.security.turnstile.*}).
 *
 * <p>{@code fingerprintHash} (Spec 141): hash SHA-256 (64 hex) dos sinais do
 * navegador — sinal de risco PASSIVO (opcional; inválido/ausente nunca quebra
 * o login).
 */
public record MerchantLoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        String totpCode,
        String turnstileToken,
        @Size(max = 64) String fingerprintHash) {

    /** Compat: requests sem token (feature Turnstile desligada por default). */
    public MerchantLoginRequest(String email, String password) {
        this(email, password, null, null, null);
    }

    /** Compat: requests sem TOTP (Spec 94 — campo opcional). */
    public MerchantLoginRequest(String email, String password, String turnstileToken) {
        this(email, password, null, turnstileToken, null);
    }
}
