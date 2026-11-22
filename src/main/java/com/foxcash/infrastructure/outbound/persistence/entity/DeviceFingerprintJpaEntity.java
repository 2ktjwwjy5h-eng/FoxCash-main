package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entidade JPA do fingerprint de dispositivo (Spec 141) — hash SHA-256 (64
 * hex) dos sinais do navegador, PK natural {@code fingerprint_hash}.
 * {@code distinct_merchants} = quantos lojistas distintos o dispositivo tocou
 * (atualizado no upsert via subquery na tabela de associação).
 */
@Entity
@Table(name = "device_fingerprints")
public class DeviceFingerprintJpaEntity {

    @Id
    @Column(name = "fingerprint_hash", length = 64)
    private String fingerprintHash;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "seen_count", nullable = false)
    private long seenCount;

    @Column(name = "distinct_merchants", nullable = false)
    private int distinctMerchants;

    protected DeviceFingerprintJpaEntity() {
    }

    public DeviceFingerprintJpaEntity(String fingerprintHash, Instant firstSeenAt, Instant lastSeenAt,
                                      long seenCount, int distinctMerchants) {
        this.fingerprintHash = fingerprintHash;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.seenCount = seenCount;
        this.distinctMerchants = distinctMerchants;
    }

    public String getFingerprintHash() {
        return fingerprintHash;
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

    public int getDistinctMerchants() {
        return distinctMerchants;
    }
}
