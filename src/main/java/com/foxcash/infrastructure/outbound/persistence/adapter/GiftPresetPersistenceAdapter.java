package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.domain.port.out.GiftPresetRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.GiftPresetMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.GiftPresetJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link GiftPresetRepository} (Spec 23).
 */
@Repository
public class GiftPresetPersistenceAdapter implements GiftPresetRepository {

    private final GiftPresetJpaRepository jpaRepository;

    public GiftPresetPersistenceAdapter(GiftPresetJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public GiftPreset save(GiftPreset preset) {
        return GiftPresetMapper.toDomain(jpaRepository.save(GiftPresetMapper.toEntity(preset)));
    }

    @Override
    public List<GiftPreset> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdOrderByAmountAsc(merchantId).stream()
                .map(GiftPresetMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<GiftPreset> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId).map(GiftPresetMapper::toDomain);
    }
}
