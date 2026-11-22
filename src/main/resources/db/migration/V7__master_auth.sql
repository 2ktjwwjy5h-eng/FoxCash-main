-- Spec 13: perfil master (operador da plataforma) com login usuario/senha.
-- Senha com hash BCrypt; sessao por token opaco (somente o hash SHA-256 e
-- persistido, mesmo padrao do api_key_hash/document_hash).
CREATE TABLE masters (
    id            UUID PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(60)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL
);

CREATE TABLE master_sessions (
    id         UUID PRIMARY KEY,
    master_id  UUID        NOT NULL REFERENCES masters(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP   NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP
);

CREATE INDEX ix_master_sessions_master_id ON master_sessions(master_id);
