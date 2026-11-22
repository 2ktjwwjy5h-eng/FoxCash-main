package com.foxcash.domain.port.out;

import com.foxcash.domain.model.notification.OutboxNotification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de saida: persistencia do outbox de notificacoes (Spec 7/35).
 */
public interface NotificationOutboxPort {

    void savePending(OutboxNotification notification);

    /** Busca por id (Spec 134 — o consumer da fila reusa o registro do DB). */
    java.util.Optional<OutboxNotification> findById(UUID id);

    /** Eventos de uma origem (Spec 125 — trace: entrega do webhook da transação). */
    List<OutboxNotification> findByEntity(String entityType, UUID entityId);

    /**
     * Último evento do lojista com erro de entrega (Spec 119 — colunas
     * "Falhas"/último erro da tela de webhooks do portal). Empty quando nenhuma
     * entrega do lojista falhou.
     */
    java.util.Optional<OutboxNotification> findLatestDeliveryError(UUID merchantId);

    /** Criação do PENDING mais antigo (Spec 126 — gauge de idade do backlog). */
    java.util.Optional<Instant> findOldestPendingCreatedAt();

    /**
     * Eventos PENDING cuja proxima tentativa venceu (ou nunca foi agendada),
     * limitados a {@code limit} — o dispatcher processa um lote por execucao
     * (auditoria 2026-08-05: limita a duracao de cada run).
     */
    List<OutboxNotification> findPendingDue(Instant now, int limit);

    void markSent(UUID id);

    /**
     * Incrementa o contador de tentativas de entrega e devolve o NOVO valor —
     * usado para decidir retry/backoff vs. FAILED sem depender de estado stale.
     */
    int incrementAttempts(UUID id);

    /**
     * Registra falha: {@code lastError} + agendamento da proxima tentativa.
     * {@code nextAttemptAt == null} => status {@code FAILED} (esgotou o max);
     * senão mantem {@code PENDING} e re-tenta em {@code nextAttemptAt}.
     */
    void markFailed(UUID id, String error, Instant nextAttemptAt);
}
