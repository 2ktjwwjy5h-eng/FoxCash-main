package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.port.out.StoreButtonRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.StoreButtonMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.StoreButtonJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link StoreButtonRepository} (Spec 50).
 */
@Repository
public class StoreButtonPersistenceAdapter implements StoreButtonRepository {

    private final StoreButtonJpaRepository jpaRepository;

    public StoreButtonPersistenceAdapter(StoreButtonJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public StoreButton save(StoreButton button) {
        return StoreButtonMapper.toDomain(jpaRepository.save(StoreButtonMapper.toEntity(button)));
    }

    @Override
    public StoreButton saveAndFlush(StoreButton button) {
        return StoreButtonMapper.toDomain(jpaRepository.saveAndFlush(StoreButtonMapper.toEntity(button)));
    }

    @Override
    public Optional<StoreButton> findByIdAndStoreId(UUID buttonId, UUID storeId) {
        return jpaRepository.findByIdAndStoreId(buttonId, storeId).map(StoreButtonMapper::toDomain);
    }

    @Override
    public List<StoreButton> findByStoreIdOrderByPosition(UUID storeId) {
        return jpaRepository.findByStoreIdOrderByPositionAsc(storeId)
                .stream().map(StoreButtonMapper::toDomain).toList();
    }

    @Override
    public List<StoreButton> findActiveByStoreIdOrderByPosition(UUID storeId) {
        return jpaRepository.findByStoreIdAndActiveTrueOrderByPositionAsc(storeId)
                .stream().map(StoreButtonMapper::toDomain).toList();
    }

    @Override
    public long countByStoreId(UUID storeId) {
        return jpaRepository.countByStoreId(storeId);
    }

    @Override
    public void delete(StoreButton button) {
        jpaRepository.delete(StoreButtonMapper.toEntity(button));
    }
}
