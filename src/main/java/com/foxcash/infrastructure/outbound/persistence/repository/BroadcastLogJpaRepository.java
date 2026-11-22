package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastLogJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para logs por destinatário do
 * broadcast (Spec 79). A idempotência usa {@code findByBroadcastIdAndRecipientId}
 * (UNIQUE {@code (broadcast_id, recipient_id)} — replay não duplica).
 */
public interface BroadcastLogJpaRepository extends JpaRepository<BroadcastLogJpaEntity, Long> {

    Optional<BroadcastLogJpaEntity> findByBroadcastIdAndRecipientId(UUID broadcastId,
                                                                     String recipientId);

    List<BroadcastLogJpaEntity> findByBroadcastIdOrderBySentAtAsc(UUID broadcastId, Pageable pageable);

    long countByBroadcastIdAndStatus(UUID broadcastId, BroadcastLogStatus status);

    long countByBroadcastId(UUID broadcastId);
}
