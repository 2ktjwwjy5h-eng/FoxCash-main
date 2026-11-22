package com.foxcash.domain.port.in;

/**
 * Porta de entrada: recuperação de senha do lojista (Spec 40).
 *
 * <p>{@code forgot-password} emite um token one-time {@code rst_<32 hex>} (hash
 * SHA-256 persistido, expira ~30min, um ativo por lojista); {@code reset-password}
 * o consome, redefine a senha (BCrypt) e revoga as sessões ativas do lojista.
 */
public interface PasswordResetUseCase {

    /** Emite um token de recuperação para o e-mail informado. */
    ForgotResult forgot(ForgotCommand command);

    /** Consome o token e redefine a senha (single-use). */
    void reset(ResetCommand command);

    /**
     * @param email e-mail do lojista (identificador de login, normalizado)
     */
    record ForgotCommand(String email) {
    }

    /**
     * @param resetToken token em claro no formato {@code rst_<32 hex>} — nunca
     *                   persistido (apenas o hash SHA-256 fica no banco)
     */
    record ForgotResult(boolean emailFound, String resetToken) {

        public static ForgotResult empty() {
            return new ForgotResult(false, null);
        }

        public static ForgotResult of(String resetToken) {
            return new ForgotResult(true, resetToken);
        }
    }

    /**
     * @param token       token em claro recebido do lojista
     * @param newPassword nova senha escolhida (8–72 caracteres)
     */
    record ResetCommand(String token, String newPassword) {
    }
}
