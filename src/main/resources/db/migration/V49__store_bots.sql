-- Spec 74 — Registro central de bots de venda (Discord + Telegram).
-- O token do bot é criptografado em repouso (CryptoConverter, `v1:` AES-256/GCM)
-- com `token_hash` SHA-256 para lookup determinístico (IV aleatório impede busca
-- no ciphertext — mesmo padrão de `document_hash`). O `webhook_secret` (32 bytes,
-- usado na URL `/webhooks/telegram/{id}/{secret}` e no header secret_token) também
-- é armazenado apenas como hash — nunca em claro.
CREATE TABLE store_bots (
    id                   UUID PRIMARY KEY,
    merchant_id          UUID        NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    platform             VARCHAR(16) NOT NULL CHECK (platform IN ('TELEGRAM', 'DISCORD')),
    token_encrypted      TEXT        NOT NULL,                -- CryptoConverter v1: (AES-256/GCM)
    token_hash           VARCHAR(64) NOT NULL UNIQUE,         -- SHA-256 hex (lookup, IV aleatório)
    webhook_secret_hash  VARCHAR(64) NOT NULL UNIQUE,         -- SHA-256 hex do secret_token/URL
    display_name         VARCHAR(255) NOT NULL,
    username             VARCHAR(255),                        -- do getMe (Telegram) / @username (Discord)
    application_id       VARCHAR(64),                         -- Discord: Application ID (opcional)
    status               VARCHAR(16) NOT NULL DEFAULT 'REGISTERING'
                         CHECK (status IN ('REGISTERING','ACTIVE','PAUSED','ERROR')),
    error_message        VARCHAR(500),
    last_health_check_at TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ,                         -- soft-delete (Spec 74: nunca apagar físico)
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_store_bots_merchant ON store_bots (merchant_id, platform) WHERE deleted_at IS NULL;
