package com.foxcash.domain.model.master;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de método de MFA do master (Spec 44) — modelagem preparada para
 * múltiplos métodos (futuro: WebAuthn/FIDO2 = novo {@code type}, tabela de
 * detalhe própria, sem refatorar o fluxo). O {@code status} cobre o ciclo
 * PENDING → ACTIVE (→ DISABLED).
 */
public class MasterMfaMethod {

    private final UUID id;
    private final UUID masterId;
    private final String type;
    private final String status;
    private final Instant createdAt;
    private final Instant activatedAt;
    private final Instant expiresAt;

    private MasterMfaMethod(UUID id, UUID masterId, String type, String status,
                            Instant createdAt, Instant activatedAt, Instant expiresAt) {
        this.id = id;
        this.masterId = masterId;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.expiresAt = expiresAt;
    }

    /** Novo método TOTP em estado PENDING (TTL para confirmar a posse do app). */
    public static MasterMfaMethod pending(UUID id, UUID masterId, Instant now, Instant expiresAt) {
        return new MasterMfaMethod(id, masterId, "TOTP", "PENDING", now, null, expiresAt);
    }

    /** Restauração a partir da persistência. */
    public static MasterMfaMethod restore(UUID id, UUID masterId, String type, String status,
                                          Instant createdAt, Instant activatedAt, Instant expiresAt) {
        return new MasterMfaMethod(id, masterId, type, status, createdAt, activatedAt, expiresAt);
    }

    public MasterMfaMethod activate(Instant now) {
        return new MasterMfaMethod(id, masterId, type, "ACTIVE", createdAt, now, null);
    }

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /** Pendente expirado? (TTL 10min — o método pendente é descartado). */
    public boolean isPendingExpired(Instant now) {
        return isPending() && expiresAt != null && now.isAfter(expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID masterId() {
        return masterId;
    }

    public String type() {
        return type;
    }

    public String status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
