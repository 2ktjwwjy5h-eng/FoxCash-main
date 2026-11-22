package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do detalhe TOTP do método de MFA do master (Spec 44).
 *
 * <p><b>Segurança (decisão 2026-08-06):</b> {@code secretEncrypted} é
 * criptografado em repouso via {@link CryptoConverter} (AES-256/GCM, formato
 * {@code v1:}) — o servidor precisa do valor original para recalcular o código
 * esperado (hash puro tornaria a verificação impossível). {@code lastCodeHash}
 * (SHA-256 do último código) garante anti-replay na janela.
 */
@Entity
@Table(name = "master_totp")
public class MasterTotpJpaEntity {

    @Id
    @Column(name = "method_id")
    private UUID methodId;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "secret_encrypted", nullable = false)
    private String secretEncrypted;

    @Column(name = "last_code_hash", length = 64)
    private String lastCodeHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected MasterTotpJpaEntity() {
    }

    public MasterTotpJpaEntity(UUID methodId, String secretEncrypted, String lastCodeHash,
                               int failedAttempts, Instant lockedUntil) {
        this.methodId = methodId;
        this.secretEncrypted = secretEncrypted;
        this.lastCodeHash = lastCodeHash;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
    }

    public UUID getMethodId() {
        return methodId;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public String getLastCodeHash() {
        return lastCodeHash;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
