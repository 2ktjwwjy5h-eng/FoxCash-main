-- Spec 83 + Spec 90 — CRM: base de clientes, chat em tempo real e handoff humano.
-- (Fundidas: implementadas juntas — a coluna handoff_status é da mesma tabela.)
--
-- bot_leads enriquecido (Spec 83 C1): perfil + status derivado do funil (Spec 75).
-- O status é DERIVADO na mesma transação dos eventos do funil (nunca manual);
-- BLOQUEADO é flag (403 "bot was blocked by the user" no envio, D6).
ALTER TABLE bot_leads ADD COLUMN name       VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN username   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN email      VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN phone      VARCHAR(50);
ALTER TABLE bot_leads ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'NOVO'
                              CHECK (status IN ('NOVO','PENDENTE','PAGO','BLOQUEADO'));
ALTER TABLE bot_leads ADD COLUMN updated_at TIMESTAMPTZ;
-- bot_leads NÃO tem merchant_id (o vínculo é via store_bots — Spec 74); o índice
-- de status é por (status) e as queries do portal filtram via join com
-- store_bots.merchant_id.
CREATE INDEX ix_bot_leads_status ON bot_leads (status);

-- Spec 90 — handoff humano (BOT automático vs HUMAN atendimento manual).
-- Sem índice: o acesso é por lead (findByBotIdAndPlatformUserId — Spec 75),
-- nunca por status.
ALTER TABLE bot_leads ADD COLUMN handoff_status VARCHAR(16) NOT NULL DEFAULT 'BOT'
                              CHECK (handoff_status IN ('BOT','HUMAN'));

CREATE TABLE chat_messages (
    id           UUID PRIMARY KEY,
    lead_id      UUID NOT NULL REFERENCES bot_leads (id) ON DELETE CASCADE,
    bot_id       UUID NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    sender_type  VARCHAR(16) NOT NULL CHECK (sender_type IN ('CLIENT','ADMIN','BOT_AUTO')),
    text_content TEXT,
    media_url    VARCHAR(500),
    is_read      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_chat_messages_lead ON chat_messages (lead_id, created_at);
