package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.NotificationOutboxJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Adaptador JPA do port NotificationOutboxPort (Spec 7/35).
 */
@Repository
public class NotificationOutboxAdapter implements NotificationOutboxPort {

    private static final String PENDING = "PENDING";
    private static final String SENT = "SENT";
    private static final String FAILED = "FAILED";

    private final NotificationOutboxJpaRepository jpaRepository;

    public NotificationOutboxAdapter(NotificationOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void savePending(OutboxNotification notification) {
        jpaRepository.save(new NotificationOutboxJpaEntity(
                notification.id(), notification.merchantId(), notification.type(),
                notification.payload(), PENDING, notification.createdAt(), null,
                notification.attempts(), notification.lastError(), notification.nextAttemptAt(),
                notification.entityType(), notification.entityId()));
    }

    @Override
    public java.util.Optional<OutboxNotification> findById(UUID id) {
        return jpaRepository.findById(id).map(NotificationOutboxAdapter::toDomain);
    }

    @Override
    public List<OutboxNotification> findByEntity(String entityType, UUID entityId) {
        return jpaRepository.findByEntityTypeAndEntityId(entityType, entityId)
                .stream().map(NotificationOutboxAdapter::toDomain).toList();
    }

    @Override
    public java.util.Optional<OutboxNotification> findLatestDeliveryError(UUID merchantId) {
        return jpaRepository.findFirstByMerchantIdAndLastErrorIsNotNullOrderByCreatedAtDesc(merchantId)
                .map(NotificationOutboxAdapter::toDomain);
    }

    @Override
    public java.util.Optional<Instant> findOldestPendingCreatedAt() {
        return jpaRepository.findOldestPendingCreatedAt();
    }

    @Override
    public List<OutboxNotification> findPendingDue(Instant now, int limit) {
        // Causa raiz Fase 2A (docs §11.6): a query unica com OR fazia Seq Scan em
        // ~2M linhas a cada poll. Dois ramos indexaveis: nunca-tentados (FIFO via
        // indice parcial V45) + retries vencidos (indice status,next_attempt_at).
        List<NotificationOutboxJpaEntity> neverAttempted =
                jpaRepository.findPendingNeverAttempted(PENDING, PageRequest.of(0, limit));
        if (neverAttempted.size() >= limit) {
            return neverAttempted.stream().map(NotificationOutboxAdapter::toDomain).toList();
        }
        int remaining = limit - neverAttempted.size();
        List<NotificationOutboxJpaEntity> retryDue = jpaRepository
                .findPendingRetryDue(PENDING, now, PageRequest.of(0, remaining));
        return Stream.concat(neverAttempted.stream(), retryDue.stream())
                .map(NotificationOutboxAdapter::toDomain)
                .toList();
    }

    @Override
    public void markSent(UUID id) {
        jpaRepository.findById(id).ifPresent(entity -> {
            entity.setStatus(SENT);
            entity.setSentAt(Instant.now());
            jpaRepository.save(entity);
        });
    }

    @Override
    public int incrementAttempts(UUID id) {
        // SEC-11 (revisão de segurança 2026-08-11): incremento ATÔMICO no banco
        // (UPDATE) — sem lost update entre processadores concorrentes do mesmo
        // evento (antes: findById + set + save, read-modify-write). O valor
        // devolvido é a leitura FRESCA após o incremento (clearAutomatically no
        // repositório garante que o findById abaixo re-carrega do banco).
        jpaRepository.incrementAttemptsAtomically(id);
        return jpaRepository.findById(id)
                .map(NotificationOutboxJpaEntity::getAttempts)
                .orElse(0);
    }

    @Override
    public void markFailed(UUID id, String error, Instant nextAttemptAt) {
        jpaRepository.findById(id).ifPresent(entity -> {
            entity.setLastError(truncate(error));
            entity.setNextAttemptAt(nextAttemptAt);
            // Sem proxima tentativa => esgotou o max (FAILED); senao mantem PENDING.
            entity.setStatus(nextAttemptAt == null ? FAILED : PENDING);
            jpaRepository.save(entity);
        });
    }

    private static OutboxNotification toDomain(NotificationOutboxJpaEntity entity) {
        return new OutboxNotification(
                entity.getId(), entity.getMerchantId(), entity.getType(), entity.getPayload(),
                entity.getCreatedAt(), entity.getAttempts(), entity.getLastError(),
                entity.getNextAttemptAt(), entity.getEntityType(), entity.getEntityId(),
                entity.getStatus());
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
