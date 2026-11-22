package com.foxcash.domain.port.out;

import com.foxcash.domain.model.auth.PasswordResetToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de tokens de recuperação de senha (Spec 40).
 */
public interface PasswordResetTokenRepository {

    PasswordResetToken save(PasswordResetToken token);

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Apaga os tokens **abertos** (não usados) do lojista — um novo
     * {@code forgot-password} invalida o token anterior (um ativo por lojista).
     */
    void deleteOpenByMerchantId(UUID merchantId);

    /**
     * Consome o token de forma ATÔMICA (SEC-10): marca {@code used_at} somente
     * se ainda estiver aberto ({@code used_at IS NULL}) e não expirado
     * ({@code expires_at > now}). Retorna {@code true} quando consumiu. Sob
     * concorrência, apenas o primeiro {@code reset} com o mesmo token vence —
     * o segundo retorna {@code false} (single-use de verdade, sem TOCTOU).
     */
    boolean consumeIfOpen(String tokenHash, Instant now);
}
