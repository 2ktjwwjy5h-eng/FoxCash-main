-- Spec 79 — Broadcast: motor de disparo em massa (Telegram/Discord).
-- Backend orquestrador (persiste/resolve/modos); o envio acontece no bot
-- (Spec 52) que busca pendências via API e reporta o log por destinatário.
-- O broadcast pode ser disparado para um canal/grupo (NOW/SCHEDULED/REPOST)
-- ou no evento de entrada de membro (WELCOME). Nada de fila externa (sem
-- BullMQ — decisão do usuário 2026-08-12).

CREATE TABLE broadcasts (
    id               UUID PRIMARY KEY,
    store_bot_id     UUID         NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    mode             VARCHAR(16)  NOT NULL CHECK (mode IN ('NOW','SCHEDULED','WELCOME','REPOST')),
    target_chat_id   VARCHAR(255),                                -- canal/grupo destino (NOW/SCHEDULED/REPOST)
    content          TEXT         NOT NULL,                       -- rich-text da plataforma + tags {primeiro_nome} etc.
    media_url        VARCHAR(500),                                -- URL da CDN (Discord / fallback)
    media_type       VARCHAR(16)  CHECK (media_type IN ('image','video','audio')),
    media_file_id    VARCHAR(255),                                -- Telegram: file_id da CDN (sem re-upload)
    buttons_json     JSONB,                                       -- grade de botões inline (link/callback)
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    error_message    VARCHAR(500),                                -- motivo do FAILED (critério 4)
    scheduled_at     TIMESTAMPTZ,                                 -- SCHEDULED (vencida → bot busca) / REPOST (próximo ciclo)
    interval_seconds INT,                                         -- REPOST: reenvio a cada intervalo (>= 3600)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Log por destinatário (reportado pelo bot via API). UNIQUE = idempotência:
-- replay de log não duplica (mesmo padrão do bot_funnel_events, Spec 75).
CREATE TABLE broadcast_logs (
    id                BIGSERIAL PRIMARY KEY,
    broadcast_id      UUID         NOT NULL REFERENCES broadcasts (id) ON DELETE CASCADE,
    recipient_id      VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('SUCCESS','BLOCKED','FAILED')),
    error_message     VARCHAR(500),
    sent_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (broadcast_id, recipient_id)
);

-- Idempotência do modo WELCOME: evento de entrada duplicado (Telegram re-envia
-- chat_member) não dispara a mensagem 2× por (chat_id, user_id).
CREATE TABLE broadcast_welcome_sent (
    id            UUID PRIMARY KEY,
    broadcast_id  UUID         NOT NULL REFERENCES broadcasts (id) ON DELETE CASCADE,
    chat_id       VARCHAR(255) NOT NULL,
    user_id       VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (chat_id, user_id)
);

-- O bot consulta pendências por (status, scheduled_at): NOW (scheduled_at NULL)
-- e SCHEDULED/REPOST vencidos. O job de REPOST re-agenda por status/modo.
CREATE INDEX ix_broadcasts_status_due ON broadcasts (status, scheduled_at);
CREATE INDEX ix_broadcast_logs_broadcast ON broadcast_logs (broadcast_id);
