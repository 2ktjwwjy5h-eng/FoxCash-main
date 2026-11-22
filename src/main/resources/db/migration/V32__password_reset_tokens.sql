-- Spec 40: recuperacao de senha do lojista (token self-service one-time).
-- Guarda apenas o hash SHA-256 do token rst_<32 hex> (nunca em claro);
-- expira em ~30min (nyxcash.auth.reset-token-ttl-seconds) e e single-use
-- (used_at). Um unico token ATIVO por lojista (indice unico parcial).

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    token_hash  VARCHAR(64) NOT NULL,              -- SHA-256 hex (nunca em claro)
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,                        -- single-use (NULL = ativo)
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);

-- No maximo um token ativo (nao usado) por lojista: um novo forgot-password
-- apaga o anterior antes de inserir; em corrida, este indice e o backstop.
CREATE UNIQUE INDEX uq_password_reset_open_per_merchant
    ON password_reset_tokens(merchant_id) WHERE used_at IS NULL;

CREATE INDEX ix_password_reset_merchant ON password_reset_tokens(merchant_id);
