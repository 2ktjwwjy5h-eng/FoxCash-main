package com.foxcash.domain.model.merchant;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de método de MFA do lojista no portal (Spec 94) — modelagem
 * preparada para múltiplos métodos (futuro: WebAuthn/FIDO2 = novo {@code type},
 * tabela de detalhe própria, sem refatorar o fluxo). O {@code status} cobre o
 * ciclo PENDING → ACTIVE (→ DISABLED).
 *
 * <p>Espelho do {@code MasterMfaMethod} (Spec 44) com {@code merchantId} no
 * lugar de {@code masterId}.
 */
public class MerchantMfaMethod {

    private final UUID id;
    private final UUID merchantId;
    private final String type;
    private final String status;
    private final Instant createdAt;
    private final Instant activatedAt;
    private final Instant expiresAt;

    private MerchantMfaMethod(UUID id, UUID merchantId, String type, String status,
                              Instant createdAt, Instant activatedAt, Instant expiresAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.expiresAt = expiresAt;
    }

    /** Novo método TOTP em estado PENDING (TTL para confirmar a posse do app). */
    public static MerchantMfaMethod pending(UUID id, UUID merchantId, Instant now, Instant expiresAt) {
        return new MerchantMfaMethod(id, merchantId, "TOTP", "PENDING", now, null, expiresAt);
    }

    /** Restauração a partir da persistência. */
    public static MerchantMfaMethod restore(UUID id, UUID merchantId, String type, String status,
                                            Instant createdAt, Instant activatedAt, Instant expiresAt) {
        return new MerchantMfaMethod(id, merchantId, type, status, createdAt, activatedAt, expiresAt);
    }

    public MerchantMfaMethod activate(Instant now) {
        return new MerchantMfaMethod(id, merchantId, type, "ACTIVE", createdAt, now, null);
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

    public UUID merchantId() {
        return merchantId;
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
