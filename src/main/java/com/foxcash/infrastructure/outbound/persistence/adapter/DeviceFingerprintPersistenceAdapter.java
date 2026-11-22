package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.fingerprint.DeviceFingerprint;
import com.foxcash.domain.port.out.DeviceFingerprintRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.DeviceFingerprintJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.DeviceFingerprintJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link DeviceFingerprintRepository} (Spec 141).
 * Escrita upsert atômica (ON CONFLICT) + refresh do contador de lojistas
 * distintos — o mesmo hash pode chegar em paralelo (cadastro/login) sem lost
 * update.
 */
@Repository
public class DeviceFingerprintPersistenceAdapter implements DeviceFingerprintRepository {

    private final DeviceFingerprintJpaRepository jpaRepository;

    public DeviceFingerprintPersistenceAdapter(DeviceFingerprintJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DeviceFingerprint record(String fingerprintHash, UUID merchantId, Instant now) {
        jpaRepository.upsertFingerprint(fingerprintHash, now);
        jpaRepository.upsertMerchantLink(fingerprintHash, merchantId, now);
        jpaRepository.refreshDistinctMerchants(fingerprintHash);
        // Snapshot com contadores pós-upsert (para a trilha de auditoria).
        DeviceFingerprintJpaEntity entity = jpaRepository.findById(fingerprintHash)
                .orElseThrow(() -> new IllegalStateException(
                        "fingerprint recém-gravado não encontrado: " + fingerprintHash));
        return toDomain(entity);
    }

    @Override
    public Optional<DeviceFingerprint> findByHash(String fingerprintHash) {
        return jpaRepository.findById(fingerprintHash).map(DeviceFingerprintPersistenceAdapter::toDomain);
    }

    @Override
    public int maxDistinctMerchantsOf(UUID merchantId) {
        return jpaRepository.maxDistinctMerchantsOf(merchantId);
    }

    private static DeviceFingerprint toDomain(DeviceFingerprintJpaEntity entity) {
        return new DeviceFingerprint(
                entity.getFingerprintHash(),
                entity.getFirstSeenAt(),
                entity.getLastSeenAt(),
                entity.getSeenCount(),
                entity.getDistinctMerchants());
    }
}
