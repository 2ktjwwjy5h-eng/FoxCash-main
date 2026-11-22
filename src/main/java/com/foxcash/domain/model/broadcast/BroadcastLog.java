package com.foxcash.domain.model.broadcast;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Log de envio por destinatário do broadcast (Spec 79) — reportado pelo bot via
 * {@code POST /api/v1/foxcash/broadcasts/{id}/logs}. Idempotente por
 * {@code (broadcast_id, recipient_id)} (UNIQUE na tabela): replay do log não
 * duplica (padrão {@code bot_funnel_events}, Spec 75).
 */
public class BroadcastLog {

    private static final int MAX_ERROR_MESSAGE = 500;

    private final UUID broadcastId;
    private final String recipientId;
    private final BroadcastLogStatus status;
    private final String errorMessage;
    private final Instant sentAt;

    private BroadcastLog(UUID broadcastId, String recipientId, BroadcastLogStatus status,
                         String errorMessage, Instant sentAt) {
        this.broadcastId = broadcastId;
        this.recipientId = recipientId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.sentAt = sentAt;
    }

    /**
     * Cria o log de um destinatário. {@code error_message} só faz sentido em
     * {@code BLOCKED}/{@code FAILED} (em {@code SUCCESS} é ignorado/normalizado
     * para {@code null}).
     */
    public static BroadcastLog create(UUID broadcastId, String recipientId,
                                      BroadcastLogStatus status, String errorMessage,
                                      Instant now) {
        Objects.requireNonNull(broadcastId, "broadcastId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (recipientId == null || recipientId.isBlank()) {
            throw new IllegalArgumentException("recipient_id e obrigatorio no log do broadcast");
        }
        String error = null;
        if (status != BroadcastLogStatus.SUCCESS && errorMessage != null && !errorMessage.isBlank()) {
            error = errorMessage.length() > MAX_ERROR_MESSAGE
                    ? errorMessage.substring(0, MAX_ERROR_MESSAGE) : errorMessage;
        }
        return new BroadcastLog(broadcastId, recipientId, status, error, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static BroadcastLog restore(UUID broadcastId, String recipientId,
                                       BroadcastLogStatus status, String errorMessage,
                                       Instant sentAt) {
        return new BroadcastLog(broadcastId, recipientId, status, errorMessage, sentAt);
    }

    public UUID broadcastId() {
        return broadcastId;
    }

    public String recipientId() {
        return recipientId;
    }

    public BroadcastLogStatus status() {
        return status;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant sentAt() {
        return sentAt;
    }
}
