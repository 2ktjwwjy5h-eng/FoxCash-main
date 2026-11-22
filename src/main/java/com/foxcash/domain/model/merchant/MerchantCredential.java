package com.foxcash.domain.model.merchant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Credencial do portal do lojista (Spec 20).
 *
 * <p>Guarda apenas o hash BCrypt da senha do portal ({@code password_hash}) — a
 * senha em claro nunca é persistida. O login do portal usa o **e-mail** como
 * identificador (Spec 39). A senha pode ser trocada no reset de senha
 * ({@link #changePassword}, Spec 40).
 */
public class MerchantCredential {

    private final UUID id;
    private final UUID merchantId;
    private String passwordHash;   // mutável: trocada no reset de senha (Spec 40)
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastUsedAt;
    private int failedLoginAttempts;
    private Instant lockedUntil;

    private MerchantCredential(UUID id, UUID merchantId, String passwordHash,
                               Instant createdAt, Instant updatedAt, Instant lastUsedAt,
                               int failedLoginAttempts, Instant lockedUntil) {
        this.id = id;
        this.merchantId = merchantId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastUsedAt = lastUsedAt;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedUntil = lockedUntil;
    }

    /**
     * Cria a credencial do portal para um lojista.
     *
     * @param passwordHash hash BCrypt da senha inicial (calculado na
     *                     infraestrutura — o domínio nunca vê a senha em claro)
     */
    public static MerchantCredential create(UUID merchantId, String passwordHash, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new MerchantCredential(UUID.randomUUID(), merchantId, passwordHash, now, now, null, 0, null);
    }

    /** Restore completo (auditoria 2026-08-05/B-06): inclui estado de lockout. */
    public static MerchantCredential restore(UUID id, UUID merchantId, String passwordHash,
                                             Instant createdAt, Instant updatedAt, Instant lastUsedAt,
                                             int failedLoginAttempts, Instant lockedUntil) {
        return new MerchantCredential(id, merchantId, passwordHash, createdAt, updatedAt, lastUsedAt,
                failedLoginAttempts, lockedUntil);
    }

    /** Overload legado (Spec 20/40 — testes existentes): credencial sem lockout. */
    public static MerchantCredential restore(UUID id, UUID merchantId, String passwordHash,
                                             Instant createdAt, Instant updatedAt, Instant lastUsedAt) {
        return restore(id, merchantId, passwordHash, createdAt, updatedAt, lastUsedAt, 0, null);
    }

    /** Registra o último login do lojista no portal (Spec 20, auditoria). */
    public void recordUse(Instant usedAt) {
        Objects.requireNonNull(usedAt, "usedAt must not be null");
        this.lastUsedAt = usedAt;
    }

    /**
     * Troca a senha do portal (Spec 40 — reset de senha). Recebe o hash BCrypt
     * da nova senha (calculado na infraestrutura — o domínio nunca vê a senha
     * em claro) e atualiza {@code updatedAt}.
     */
    public void changePassword(String newPasswordHash, Instant now) {
        Objects.requireNonNull(newPasswordHash, "newPasswordHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        this.passwordHash = newPasswordHash;
        this.updatedAt = now;
    }

    /** Bloqueada no instante {@code now}? (auditoria 2026-08-05/B-06). */
    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Registra uma tentativa de login com falha (auditoria 2026-08-05/B-06).
     * Ao atingir {@code maxAttempts}, seta {@code lockedUntil} e zera o contador
     * (após a janela expirar o lojista tem tentativas novas).
     */
    public void registerFailedLogin(Instant now, int maxAttempts, java.time.Duration lockoutDuration) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockoutDuration);
            failedLoginAttempts = 0;
        }
    }

    /** Login bem-sucedido: limpa contador e lockout (auditoria 2026-08-05/B-06). */
    public void registerSuccessfulLogin() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Último login no portal (null se o lojista nunca logou) — auditoria (Spec 20). */
    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    /** Falhas seguidas de senha sem sucesso (auditoria 2026-08-05/B-06). */
    public int failedLoginAttempts() {
        return failedLoginAttempts;
    }

    /** Instante até o qual a conta fica bloqueada (null = desbloqueada). */
    public Instant lockedUntil() {
        return lockedUntil;
    }
}
