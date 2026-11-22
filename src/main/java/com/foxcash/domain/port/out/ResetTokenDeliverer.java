package com.foxcash.domain.port.out;

import java.time.Instant;

/**
 * Porta de saida: entrega do token de recuperacao de senha (Spec 45).
 *
 * <p>O {@code forgot-password} gera o token {@code rst_<32 hex>} (Spec 40) e o
 * entrega por este canal (e-mail em producao; log em dev/testes). A assinatura
 * {@code deliver(String, String, Instant)} lança em falha — o chamador decide
 * como tratar (o {@code forgot-password} sempre responde 204, sem vazar
 * existência nem derrubar o fluxo por e-mail quebrado).
 */
public interface ResetTokenDeliverer {

    /**
     * Entrega o token de recuperação ao endereço da conta.
     *
     * @param email       e-mail do lojista (identificador de login, normalizado)
     * @param resetToken  token em claro {@code rst_<32 hex>} — NUNCA vai para
     *                    resposta HTTP nem logs de produção
     * @param expiresAt   momento de expiração (para instruções no e-mail)
     * @throws RuntimeException se a entrega falhar (SMTP indisponível, etc.)
     */
    void deliver(String email, String resetToken, Instant expiresAt);
}
