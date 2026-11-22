package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) do outbox de notificacoes.
 */
public interface NotificationOutboxJpaRepository extends JpaRepository<NotificationOutboxJpaEntity, UUID> {

    /**
     * PENDING nunca tentados ({@code next_attempt_at IS NULL} — caso dominante
     * do backlog) em ordem FIFO ({@code created_at}). Divisao da query original
     * (causa raiz Fase 2A, docs §11.5/11.6): o predicado com OR impedia o uso do
     * indice e cada poll rodava Seq Scan em ~2M linhas. A ordem FIFO casa com o
     * indice parcial {@code ix_notification_outbox_pending_created} (V45) — o
     * banco anda pelo indice e para no {@link Pageable}.
     */
    @Query("SELECT e FROM NotificationOutboxJpaEntity e WHERE e.status = :status " +
            "AND e.nextAttemptAt IS NULL ORDER BY e.createdAt")
    List<NotificationOutboxJpaEntity> findPendingNeverAttempted(@Param("status") String status,
                                                                Pageable pageable);

    /**
     * PENDING em retry cuja proxima tentativa venceu — subconjunto pequeno,
     * atende pelo indice {@code ix_notification_outbox_pending (status,
     * next_attempt_at)}.
     */
    @Query("SELECT e FROM NotificationOutboxJpaEntity e WHERE e.status = :status " +
            "AND e.nextAttemptAt IS NOT NULL AND e.nextAttemptAt <= :now ORDER BY e.nextAttemptAt")
    List<NotificationOutboxJpaEntity> findPendingRetryDue(@Param("status") String status,
                                                          @Param("now") Instant now,
                                                          Pageable pageable);

    /**
     * SEC-11 (revisão de segurança 2026-08-11): incremento ATÔMICO do contador
     * de tentativas (UPDATE no banco) — sem lost update entre processadores
     * concorrentes do mesmo evento (antes: findById + set + save, read-modify-
     * write). Devolve o nº de linhas afetadas (1 = incrementou; 0 = inexistente).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationOutboxJpaEntity e set e.attempts = e.attempts + 1 where e.id = :id")
    int incrementAttemptsAtomically(@Param("id") UUID id);

    /** Eventos de uma origem (Spec 125 — trace: entrega do webhook da transação). */
    List<NotificationOutboxJpaEntity> findByEntityTypeAndEntityId(String entityType, UUID entityId);

    /**
     * Último evento do lojista com erro de entrega (Spec 119 — colunas
     * "Falhas"/último erro da tela de webhooks): o mais recente com
     * {@code last_error} não-nulo. Vazio quando nenhuma entrega falhou.
     */
    Optional<NotificationOutboxJpaEntity> findFirstByMerchantIdAndLastErrorIsNotNullOrderByCreatedAtDesc(
            UUID merchantId);

    /** Criação do PENDING mais antigo (Spec 126 — gauge de idade do backlog). */
    @Query("SELECT MIN(e.createdAt) FROM NotificationOutboxJpaEntity e WHERE e.status = 'PENDING'")
    Optional<java.time.Instant> findOldestPendingCreatedAt();
}
