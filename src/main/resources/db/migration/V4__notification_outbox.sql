-- Outbox de notificacoes (Spec 7): eventos pendentes de entrega ao lojista.
CREATE TABLE notification_outbox (
    id          UUID PRIMARY KEY,
    merchant_id UUID        NOT NULL,
    type        VARCHAR(50) NOT NULL,
    payload     TEXT        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP   NOT NULL,
    sent_at     TIMESTAMP,
    CONSTRAINT fk_notification_outbox_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id)
);

CREATE INDEX idx_notification_outbox_status ON notification_outbox (status);
