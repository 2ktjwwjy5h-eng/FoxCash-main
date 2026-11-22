-- Spec 20: portal do lojista — credenciais (login usuario/senha) + sessao.
-- Senha com hash BCrypt; sessao por token opaco (somente o hash SHA-256 e
-- persistido, mesmo padrao de master_sessions/document_hash/api_key_hash).
CREATE TABLE merchant_credentials (
    id            UUID PRIMARY KEY,
    merchant_id   UUID        NOT NULL REFERENCES merchants(id),
    password_hash VARCHAR(60) NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    CONSTRAINT uq_merchant_credentials_merchant UNIQUE (merchant_id)
);

CREATE TABLE merchant_sessions (
    id          UUID PRIMARY KEY,
    merchant_id UUID        NOT NULL REFERENCES merchants(id),
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMP   NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    revoked_at  TIMESTAMP
);

CREATE INDEX ix_merchant_sessions_merchant_id ON merchant_sessions(merchant_id);
