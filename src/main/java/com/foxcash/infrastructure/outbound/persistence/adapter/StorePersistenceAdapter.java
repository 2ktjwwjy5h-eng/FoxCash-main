package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.out.StoreRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.StoreMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.StoreJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link StoreRepository} (Spec 48).
 */
@Repository
public class StorePersistenceAdapter implements StoreRepository {

    private final StoreJpaRepository jpaRepository;

    public StorePersistenceAdapter(StoreJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Store save(Store store) {
        return StoreMapper.toDomain(jpaRepository.save(StoreMapper.toEntity(store)));
    }

    @Override
    public Optional<Store> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantId(merchantId).map(StoreMapper::toDomain);
    }

    @Override
    public Optional<Store> findByIdAndMerchantId(UUID storeId, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(storeId, merchantId).map(StoreMapper::toDomain);
    }

    @Override
    public boolean existsByMerchantId(UUID merchantId) {
        return jpaRepository.existsByMerchantId(merchantId);
    }
}
