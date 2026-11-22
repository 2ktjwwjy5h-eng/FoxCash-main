package com.foxcash.domain.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: autenticação do lojista no portal (Spec 20, identificador
 * por e-mail desde a Spec 39).
 *
 * <p>Login valida **e-mail** + senha BCrypt, emite sessão com token opaco
 * (somente o hash SHA-256 é persistido). Logout revoga a sessão.
 */
public interface MerchantPortalAuthUseCase {

    LoginResult login(LoginCommand command);

    void logout(String rawToken);

    /**
     * @param email           e-mail do lojista — identificador único (normalizado
     *                        em minúsculas)
     * @param password        senha escolhida no cadastro
     * @param totpCode        2º fator opcional (Spec 94) — exigido apenas quando
     *                        o lojista tem MFA ativo (TOTP de 6 dígitos ou
     *                        recovery code)
     * @param fingerprintHash hash SHA-256 (64 hex) do navegador (Spec 141) —
     *                        sinal de risco PASSIVO; {@code null} quando ausente
     */
    record LoginCommand(String email, String password, String totpCode, String fingerprintHash) {

        /** Compat: requests sem MFA (Spec 94 — campo opcional). */
        public LoginCommand(String email, String password) {
            this(email, password, null, null);
        }

        /** Compat: requests sem fingerprint (Spec 141 — campo opcional). */
        public LoginCommand(String email, String password, String totpCode) {
            this(email, password, totpCode, null);
        }
    }

    /**
     * @param token      token opaco em claro (devolvido UMA vez no login)
     * @param merchantId id do lojista autenticado
     * @param name       nome do lojista (Spec 39)
     * @param expiresAt  expiração da sessão
     */
    record LoginResult(String token, UUID merchantId, String name, Instant expiresAt) {
    }
}
