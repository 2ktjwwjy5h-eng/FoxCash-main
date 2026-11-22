-- Spec 35: webhooks de saida para o bot do lojista.
-- merchant_webhooks: configuracao de webhook por lojista (URL + segredo
-- criptografado + eventos assinados). O outbox ganha controle de entrega/retry.

CREATE TABLE merchant_webhooks (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    url          VARCHAR(500) NOT NULL,
    secret       VARCHAR(255) NOT NULL,     -- criptografado AES-256/GCM (v1:)
    events       VARCHAR(255) NOT NULL,     -- lista CSV de tipos (ex.: SALE_PAID,CHARGE_CREATED)
    status       VARCHAR(20) NOT NULL,      -- ACTIVE | INACTIVE
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);

CREATE INDEX ix_merchant_webhooks_merchant ON merchant_webhooks(merchant_id);
CREATE INDEX ix_merchant_webhooks_merchant_status ON merchant_webhooks(merchant_id, status);

-- Outbox ganha controle de entrega/retry (Spec 35).
ALTER TABLE notification_outbox
    ADD COLUMN attempts        INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error      VARCHAR(500),
    ADD COLUMN next_attempt_at TIMESTAMP;

CREATE INDEX ix_notification_outbox_pending ON notification_outbox(status, next_attempt_at);
