package com.foxcash.domain.model.merchant;

import java.time.Instant;
import java.util.UUID;

/**
 * Detalhe TOTP do método de MFA do lojista no portal (Spec 94).
 *
 * <p><b>Segurança (espelho do master, decisão 2026-08-06):</b> o
 * {@code secretEncrypted} é o secret base32 **criptografado em repouso**
 * (CryptoConverter AES-256/GCM, formato {@code v1:}) — o servidor precisa do
 * valor original para recalcular o código esperado (hash puro tornaria a
 * verificação impossível). O {@code lastCodeHash} (SHA-256 do último código
 * usado) garante **anti-replay** na janela; {@code failedAttempts}/
 * {@code lockedUntil} implementam o lockout de TOTP (expira sozinho — sem
 * unlock manual).
 */
public class MerchantTotp {

    private final UUID methodId;
    private final String secretEncrypted;
    private final String lastCodeHash;
    private final int failedAttempts;
    private final Instant lockedUntil;

    private MerchantTotp(UUID methodId, String secretEncrypted, String lastCodeHash,
                         int failedAttempts, Instant lockedUntil) {
        this.methodId = methodId;
        this.secretEncrypted = secretEncrypted;
        this.lastCodeHash = lastCodeHash;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
    }

    public static MerchantTotp create(UUID methodId, String secretEncrypted, Instant now) {
        return new MerchantTotp(methodId, secretEncrypted, null, 0, null);
    }

    public static MerchantTotp restore(UUID methodId, String secretEncrypted, String lastCodeHash,
                                       int failedAttempts, Instant lockedUntil) {
        return new MerchantTotp(methodId, secretEncrypted, lastCodeHash, failedAttempts, lockedUntil);
    }

    /** Registra uso de código válido (anti-replay: hash do código usado). */
    public MerchantTotp recordValidCode(String codeHash, Instant now) {
        return new MerchantTotp(methodId, secretEncrypted, codeHash, 0, null);
    }

    /** Incrementa falhas; ao atingir o máximo, aplica o lockout (expira sozinho). */
    public MerchantTotp recordFailedAttempt(int maxAttempts, long lockoutMinutes, Instant now) {
        int attempts = failedAttempts + 1;
        Instant locked = attempts >= maxAttempts
                ? now.plusSeconds(lockoutMinutes * 60)
                : null;
        return new MerchantTotp(methodId, secretEncrypted, lastCodeHash, attempts, locked);
    }

    /** Lockout ativo? (expira automaticamente — não há unlock manual). */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && !now.isAfter(lockedUntil);
    }

    public UUID methodId() {
        return methodId;
    }

    public String secretEncrypted() {
        return secretEncrypted;
    }

    public String lastCodeHash() {
        return lastCodeHash;
    }

    public int failedAttempts() {
        return failedAttempts;
    }

    public Instant lockedUntil() {
        return lockedUntil;
    }
}
