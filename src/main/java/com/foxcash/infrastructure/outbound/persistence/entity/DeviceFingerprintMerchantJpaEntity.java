package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA do vínculo fingerprint ↔ lojista (Spec 141) — chave composta
 * {@code (fingerprint_hash, merchant_id)}. 1 dispositivo em N contas = sinal
 * de risco (mesmo hash aparecendo em cadastros/logins de lojistas distintos).
 */
@Entity
@Table(name = "device_fingerprint_merchants")
public class DeviceFingerprintMerchantJpaEntity {

    @EmbeddedId
    private DeviceFingerprintMerchantId id;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "seen_count", nullable = false)
    private long seenCount;

    protected DeviceFingerprintMerchantJpaEntity() {
    }

    public DeviceFingerprintMerchantJpaEntity(String fingerprintHash, UUID merchantId,
                                              Instant firstSeenAt, Instant lastSeenAt, long seenCount) {
        this.id = new DeviceFingerprintMerchantId(fingerprintHash, merchantId);
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.seenCount = seenCount;
    }

    public DeviceFingerprintMerchantId getId() {
        return id;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public long getSeenCount() {
        return seenCount;
    }

    @Embeddable
    public static class DeviceFingerprintMerchantId implements Serializable {

        @Column(name = "fingerprint_hash", length = 64)
        private String fingerprintHash;

        @Column(name = "merchant_id")
        private UUID merchantId;

        protected DeviceFingerprintMerchantId() {
        }

        public DeviceFingerprintMerchantId(String fingerprintHash, UUID merchantId) {
            this.fingerprintHash = fingerprintHash;
            this.merchantId = merchantId;
        }

        public String getFingerprintHash() {
            return fingerprintHash;
        }

        public UUID getMerchantId() {
            return merchantId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DeviceFingerprintMerchantId that)) {
                return false;
            }
            return Objects.equals(fingerprintHash, that.fingerprintHash)
                    && Objects.equals(merchantId, that.merchantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fingerprintHash, merchantId);
        }
    }
}
