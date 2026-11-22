package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryActionExecJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para ações de entrega em EXECUÇÃO
 * (Spec 49).
 */
public interface PlanDeliveryActionExecJpaRepository extends JpaRepository<PlanDeliveryActionExecJpaEntity, UUID> {

    /** Leitura (sem lock) — usada por GET /deliveries/* (transação read-only). */
    List<PlanDeliveryActionExecJpaEntity> findByDeliveryIdOrderBySeqAsc(UUID deliveryId);

    /**
     * Escrita com lock pessimista (SELECT ... FOR UPDATE): serializa 2 conclusões
     * CONCORRENTES da mesma entrega — o 2º request bloqueia, relê a ação já
     * concluída e vira replay idempotente (1 execução, 1 auditoria). Sem o lock,
     * 2 threads liam PENDING e executavam a ação 2× (bug exposto pelo E2E
     * concurrency.spec.js cenário 4, 2026-08-07). Usado APENAS em transações de
     * ESCRITA (DeliveryActionCompletionService) — não em leituras read-only.
     * Nome NÃO-derivado ("ForUpdate" não é property) → @Query explícita.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PlanDeliveryActionExecJpaEntity e WHERE e.deliveryId = :deliveryId ORDER BY e.seq ASC")
    List<PlanDeliveryActionExecJpaEntity> findByDeliveryIdForUpdate(@Param("deliveryId") UUID deliveryId);

    Optional<PlanDeliveryActionExecJpaEntity> findByIdAndDeliveryId(UUID execId, UUID deliveryId);
}
