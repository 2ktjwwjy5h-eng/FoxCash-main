package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.apikey.ApiKeyStatus;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantApiKeyJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantApiKeyMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantApiKeyJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantApiKeyRepository} (Spec 24).
 */
@Repository
public class MerchantApiKeyPersistenceAdapter implements MerchantApiKeyRepository {

    private final MerchantApiKeyJpaRepository jpaRepository;

    public MerchantApiKeyPersistenceAdapter(MerchantApiKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantApiKey save(MerchantApiKey key) {
        return MerchantApiKeyMapper.toDomain(jpaRepository.save(MerchantApiKeyMapper.toEntity(key)));
    }

    @Override
    public Optional<MerchantApiKey> findByKeyHash(String keyHash) {
        return jpaRepository.findByKeyHash(keyHash).map(MerchantApiKeyMapper::toDomain);
    }

    @Override
    public List<MerchantApiKey> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(MerchantApiKeyMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<MerchantApiKey> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId).map(MerchantApiKeyMapper::toDomain);
    }

    @Override
    public long countActiveByMerchantId(UUID merchantId) {
        return jpaRepository.countByMerchantIdAndStatus(merchantId, ApiKeyStatus.ACTIVE.name());
    }

    @Override
    public void recordUse(UUID keyId, Instant usedAt) {
        jpaRepository.findById(keyId).ifPresent(entity -> {
            entity.setLastUsedAt(usedAt);
            jpaRepository.save(entity);
        });
    }
}
