package com.foxcash.domain.model.master;

import java.time.Instant;
import java.util.UUID;

/**
 * Código de recuperação do MFA do master (Spec 44) — SHA-256 single-use.
 * Comparado **diretamente por hash** (tempo constante via
 * {@code MessageDigest.isEqual}) — nunca usado para gerar códigos, então o
 * hash puro é adequado (mesmo padrão dos tokens de sessão/reset).
 */
public class MasterRecoveryCode {

    private final UUID id;
    private final UUID methodId;
    private final String codeHash;
    private final Instant usedAt;
    private final Instant createdAt;

    private MasterRecoveryCode(UUID id, UUID methodId, String codeHash,
                               Instant usedAt, Instant createdAt) {
        this.id = id;
        this.methodId = methodId;
        this.codeHash = codeHash;
        this.usedAt = usedAt;
        this.createdAt = createdAt;
    }

    public static MasterRecoveryCode create(UUID id, UUID methodId, String codeHash, Instant now) {
        return new MasterRecoveryCode(id, methodId, codeHash, null, now);
    }

    public static MasterRecoveryCode restore(UUID id, UUID methodId, String codeHash,
                                             Instant usedAt, Instant createdAt) {
        return new MasterRecoveryCode(id, methodId, codeHash, usedAt, createdAt);
    }

    public MasterRecoveryCode use(Instant now) {
        return new MasterRecoveryCode(id, methodId, codeHash, now, createdAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID methodId() {
        return methodId;
    }

    public String codeHash() {
        return codeHash;
    }

    public Instant usedAt() {
        return usedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
