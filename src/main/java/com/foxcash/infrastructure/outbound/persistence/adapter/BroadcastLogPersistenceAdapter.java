package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.BroadcastLogMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.BroadcastLogJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link BroadcastLogRepository} (Spec 79).
 */
@Repository
public class BroadcastLogPersistenceAdapter implements BroadcastLogRepository {

    /** Tamanho fixo da página de logs no detalhe do portal (Spec 79 — D9). */
    public static final int LOGS_PAGE_SIZE = 50;

    private final BroadcastLogJpaRepository jpaRepository;

    public BroadcastLogPersistenceAdapter(BroadcastLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public BroadcastLog save(BroadcastLog log) {
        return BroadcastLogMapper.toDomain(jpaRepository.save(BroadcastLogMapper.toEntity(log)));
    }

    @Override
    public Optional<BroadcastLog> findByBroadcastIdAndRecipientId(UUID broadcastId, String recipientId) {
        return jpaRepository.findByBroadcastIdAndRecipientId(broadcastId, recipientId)
                .map(BroadcastLogMapper::toDomain);
    }

    @Override
    public List<BroadcastLog> findByBroadcastId(UUID broadcastId) {
        return jpaRepository.findByBroadcastIdOrderBySentAtAsc(broadcastId,
                        PageRequest.of(0, LOGS_PAGE_SIZE))
                .stream().map(BroadcastLogMapper::toDomain).toList();
    }

    @Override
    public long countByBroadcastIdAndStatus(UUID broadcastId, BroadcastLogStatus status) {
        return jpaRepository.countByBroadcastIdAndStatus(broadcastId, status);
    }
}
