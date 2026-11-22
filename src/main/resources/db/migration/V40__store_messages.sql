-- Spec 51 (mensagens automáticas + agendamento): parte do modelo da loja.
-- O vendedor edita os textos no painel; o bot apenas renderiza (Spec 52).
--
-- store_messages: 1 mensagem por etapa da venda por loja
--   (unique (store_id, event)) — event: WELCOME/AWAITING_PAYMENT/
--   PAYMENT_RECEIVED/DELIVERED/PROMO/FAQ. O text pode conter link privado —
--   nunca vai em claro para logs/auditoria (SensitiveDataLoggingFilter mascara
--   a chave "text"; a atividade registra apenas o event).
--
-- store_scheduled_messages: agendamento de envio em massa (promoção etc.).
--   One-shot (scheduled_at) OU recorrente (cron de 6 campos Quartz —
--   validado no domínio). audience = BUYERS nesta versão (o backend resolve os
--   destinatários: platform_user_id dos compradores entregues — Spec 49; ALL
--   fica como evolução). status: SCHEDULED -> SENT (confirmação do bot).

CREATE TABLE store_messages (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    event VARCHAR(40) NOT NULL,
    text TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_store_messages_store_event UNIQUE (store_id, event)
);
CREATE INDEX ix_store_messages_store ON store_messages (store_id);

CREATE TABLE store_scheduled_messages (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(id),
    text TEXT NOT NULL,
    scheduled_at TIMESTAMP,
    cron VARCHAR(100),
    audience VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_store_scheduled_messages_store_status
    ON store_scheduled_messages (store_id, status, scheduled_at);
