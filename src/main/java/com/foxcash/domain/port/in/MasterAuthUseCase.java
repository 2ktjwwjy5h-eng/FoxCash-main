package com.foxcash.domain.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: autenticação do perfil master (Spec 13).
 */
public interface MasterAuthUseCase {

    LoginResult login(LoginCommand command);

    void logout(String rawToken);

    /**
     * Troca a senha do perfil master autenticado (SEC-25, revisão de segurança
     * 2026-08-11): exige a senha ATUAL (verificada com BCrypt — tempo uniforme,
     * sem oráculo), aplica a política de senha e revoga TODAS as sessões do
     * master (a rotação mata tokens roubados no instante da troca).
     *
     * @param masterId        id do master autenticado (principal da rota)
     * @param currentPassword senha atual (verificada contra o hash BCrypt)
     * @param newPassword     nova senha (8–72 + complexidade — PasswordPolicy)
     */
    void changePassword(UUID masterId, String currentPassword, String newPassword);

    record LoginCommand(String username, String password, String totpCode) {

        public LoginCommand {
            // Conveniência: o parâmetro TOTP pode ser null (master sem MFA ativo).
        }

        public static LoginCommand of(String username, String password) {
            return new LoginCommand(username, password, null);
        }

        public static LoginCommand of(String username, String password, String totpCode) {
            return new LoginCommand(username, password, totpCode);
        }
    }

    /**
     * @param token     token opaco em claro (devolvido UMA vez no login)
     * @param masterId  id do master autenticado
     * @param name      nome do master
     * @param expiresAt expiração da sessão
     */
    record LoginResult(String token, UUID masterId, String name, Instant expiresAt) {
    }
}
