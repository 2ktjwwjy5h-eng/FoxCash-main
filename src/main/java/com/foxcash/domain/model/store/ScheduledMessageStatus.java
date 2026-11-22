package com.foxcash.domain.model.store;

/**
 * Estado de uma mensagem programada (Spec 51): {@code SCHEDULED} (aguardando o
 * bot buscar e enviar) → {@code SENT} (o bot confirmou o envio via
 * {@code POST /api/v1/foxcash/scheduled-messages/{id}/sent} — idempotente).
 */
public enum ScheduledMessageStatus {
    SCHEDULED,
    SENT
}
