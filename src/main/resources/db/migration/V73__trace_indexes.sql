-- Spec 125 — Trace de transação: rastreio ponta a ponta.
-- Fase 1: o trace consulta a platform_activity_log por entidade (entityType/entityId)
-- — sem índice seria full-scan (inaceitável com volume).
-- Fase 2: notification_outbox ganha referência de origem para o trace mostrar a
-- entrega do webhook (attempts/lastError/status) por transação.

-- Índice da activity por entidade (spec 125, D6.1).
CREATE INDEX ix_platform_activity_entity
    ON platform_activity_log (entity_type, entity_id, created_at);

-- Referência da origem no outbox (spec 125, D6.2).
ALTER TABLE notification_outbox
    ADD COLUMN entity_type VARCHAR(64),
    ADD COLUMN entity_id UUID;

CREATE INDEX ix_outbox_entity
    ON notification_outbox (entity_type, entity_id, created_at);

-- Backfill: eventos transacionais têm `data.transactionId` no payload JSON
-- (WebhookEnvelope {eventId, type, merchantId, createdAt, data} — CHARGE_CREATED
-- e SALE_PAID/SALE_REFUNDED confirmados com transactionId no data).
UPDATE notification_outbox
SET entity_type = 'TRANSACTION',
    entity_id   = (payload::jsonb -> 'data' ->> 'transactionId')::uuid
WHERE type IN ('CHARGE_CREATED', 'SALE_PAID', 'SALE_REFUNDED')
  AND payload::jsonb -> 'data' ->> 'transactionId' IS NOT NULL;
