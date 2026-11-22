package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageStatus;
import com.foxcash.domain.port.out.ScheduledMessageRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.ScheduledMessageMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ScheduledMessageJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link ScheduledMessageRepository} (Spec 51).
 */
@Repository
public class ScheduledMessagePersistenceAdapter implements ScheduledMessageRepository {

    private final ScheduledMessageJpaRepository jpaRepository;

    public ScheduledMessagePersistenceAdapter(ScheduledMessageJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ScheduledMessage save(ScheduledMessage message) {
        return ScheduledMessageMapper.toDomain(jpaRepository.save(ScheduledMessageMapper.toEntity(message)));
    }

    @Override
    public Optional<ScheduledMessage> findByIdAndStoreId(UUID messageId, UUID storeId) {
        return jpaRepository.findByIdAndStoreId(messageId, storeId).map(ScheduledMessageMapper::toDomain);
    }

    @Override
    public List<ScheduledMessage> findByStoreId(UUID storeId) {
        return jpaRepository.findByStoreIdOrderByCreatedAtAsc(storeId)
                .stream().map(ScheduledMessageMapper::toDomain).toList();
    }

    @Override
    public List<ScheduledMessage> findByStoreIdAndStatus(UUID storeId, ScheduledMessageStatus status) {
        return jpaRepository.findByStoreIdAndStatusOrderByCreatedAtAsc(storeId, status)
                .stream().map(ScheduledMessageMapper::toDomain).toList();
    }
}
