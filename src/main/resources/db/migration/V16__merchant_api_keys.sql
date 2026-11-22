-- Spec 24 (2026-08-04): chaves de API gerenciáveis por lojista (portal).
-- A chave do cadastro (Spec 12, merchants.api_key_hash) é migrada para esta
-- tabela com escopo total — integrações existentes não quebram (mesmo hash,
-- mesmo prefixo mch_). Novas chaves são criadas via /portal/credentials.
CREATE TABLE merchant_api_keys (
    id           UUID PRIMARY KEY,
    merchant_id  UUID         NOT NULL REFERENCES merchants(id),
    label        VARCHAR(80)  NOT NULL,
    prefix       VARCHAR(16)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    scopes       VARCHAR(255) NOT NULL,
    last_used_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT uq_merchant_api_keys_key_hash UNIQUE (key_hash)
);

CREATE INDEX ix_merchant_api_keys_merchant_id ON merchant_api_keys(merchant_id);
CREATE INDEX ix_merchant_api_keys_status ON merchant_api_keys(status);

-- Migra a chave única do cadastro (escopo total; prefixo mch_ conservado).
INSERT INTO merchant_api_keys (id, merchant_id, label, prefix, key_hash, status, scopes, last_used_at, created_at)
SELECT gen_random_uuid(), id, 'Chave do cadastro', 'mch_', api_key_hash, 'ACTIVE',
       'charges,transactions,withdrawals', NULL, created_at
FROM merchants
WHERE api_key_hash IS NOT NULL;
