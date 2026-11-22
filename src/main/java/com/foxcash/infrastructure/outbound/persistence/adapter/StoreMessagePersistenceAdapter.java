package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.out.StoreMessageRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.StoreMessageMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.StoreMessageJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link StoreMessageRepository} (Spec 51).
 */
@Repository
public class StoreMessagePersistenceAdapter implements StoreMessageRepository {

    private final StoreMessageJpaRepository jpaRepository;

    public StoreMessagePersistenceAdapter(StoreMessageJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public StoreMessage save(StoreMessage message) {
        return StoreMessageMapper.toDomain(jpaRepository.save(StoreMessageMapper.toEntity(message)));
    }

    @Override
    public Optional<StoreMessage> findByIdAndStoreId(UUID messageId, UUID storeId) {
        return jpaRepository.findByIdAndStoreId(messageId, storeId).map(StoreMessageMapper::toDomain);
    }

    @Override
    public Optional<StoreMessage> findByStoreIdAndEvent(UUID storeId, StoreMessageEvent event) {
        return jpaRepository.findByStoreIdAndEvent(storeId, event).map(StoreMessageMapper::toDomain);
    }

    @Override
    public List<StoreMessage> findByStoreId(UUID storeId) {
        return jpaRepository.findByStoreIdOrderByEventAsc(storeId)
                .stream().map(StoreMessageMapper::toDomain).toList();
    }

    @Override
    public List<StoreMessage> findActiveByStoreId(UUID storeId) {
        return jpaRepository.findByStoreIdAndActiveTrueOrderByEventAsc(storeId)
                .stream().map(StoreMessageMapper::toDomain).toList();
    }

    @Override
    public void delete(StoreMessage message) {
        jpaRepository.delete(StoreMessageMapper.toEntity(message));
    }
}
