package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.store.ScheduledMessageStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.ScheduledMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para mensagens programadas da loja
 * (Spec 51).
 */
public interface ScheduledMessageJpaRepository extends JpaRepository<ScheduledMessageJpaEntity, UUID> {

    Optional<ScheduledMessageJpaEntity> findByIdAndStoreId(UUID messageId, UUID storeId);

    List<ScheduledMessageJpaEntity> findByStoreIdOrderByCreatedAtAsc(UUID storeId);

    List<ScheduledMessageJpaEntity> findByStoreIdAndStatusOrderByCreatedAtAsc(
            UUID storeId, ScheduledMessageStatus status);
}
