-- Spec 75 — Leads e métricas por bot.
-- bot_leads: usuário único que inicia conversa com o bot (UNIQUE bot_id+platform_user_id,
-- padrão da ideia externa, validado no backend). Idempotência: replay de update
-- (Telegram) / interação (Discord) não duplica — UNIQUE + insert com catch no serviço.
CREATE TABLE bot_leads (
    id                UUID PRIMARY KEY,
    bot_id            UUID        NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    platform_user_id  VARCHAR(255) NOT NULL,
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bot_id, platform_user_id)
);

-- bot_funnel_events: funil lead → checkout_started → paid por (bot, usuário).
-- `paid` é idempotente por (bot_id, transaction_id) — replay de webhook não duplica
-- (o serviço checa antes do insert; o PAID é gravado na MESMA transação do webhook,
-- Spec 75/F1). transaction_id NULL (lead/checkout ainda sem transação definida —
-- checkout tem, lead não).
CREATE TABLE bot_funnel_events (
    id                UUID PRIMARY KEY,
    bot_id            UUID        NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    platform_user_id  VARCHAR(255) NOT NULL,
    event             VARCHAR(16) NOT NULL CHECK (event IN ('lead','checkout_started','paid')),
    transaction_id    UUID        NULL REFERENCES pix_transactions (id),
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_bot_funnel_bot_time ON bot_funnel_events (bot_id, occurred_at);
CREATE INDEX ix_bot_funnel_transaction ON bot_funnel_events (transaction_id) WHERE transaction_id IS NOT NULL;

-- Spec 75 (T1.4): atribuição `bot_id` na cobrança — coluna anulável, sem quebrar o
-- contrato existente (purchase sem botId → NULL, comportamento atual). Índice parcial
-- para as queries de métricas (vendas/receita por bot) sem custo em transações avulsas.
ALTER TABLE pix_transactions ADD COLUMN bot_id UUID NULL REFERENCES store_bots (id);
CREATE INDEX ix_pix_transactions_bot ON pix_transactions (bot_id) WHERE bot_id IS NOT NULL;
