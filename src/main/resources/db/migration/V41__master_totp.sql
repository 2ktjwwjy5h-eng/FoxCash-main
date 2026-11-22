-- Spec 44 — MFA 2FA TOTP para o master (B-A5).
-- Modelagem preparada para múltiplos métodos de MFA (decisão 2026-08-06):
-- master_mfa_methods = registro (type/status); master_totp = detalhe TOTP;
-- master_recovery_codes = códigos de recuperação single-use (SHA-256).

CREATE TABLE master_mfa_methods (
    id           UUID PRIMARY KEY,
    master_id    UUID NOT NULL REFERENCES masters(id),
    type         VARCHAR(20) NOT NULL,          -- 'TOTP' (futuro: 'WEBAUTHN')
    status       VARCHAR(20) NOT NULL,          -- PENDING | ACTIVE | DISABLED
    created_at   TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ                    -- TTL do estado PENDING (10min)
);

CREATE INDEX ix_master_mfa_methods_master ON master_mfa_methods(master_id);

-- Detalhe TOTP: secret SEMPRE criptografado em repouso (CryptoConverter,
-- AES-256/GCM, formato v1:) — o servidor precisa do valor original para
-- recalcular o código esperado (hash puro tornaria a verificação impossível).
CREATE TABLE master_totp (
    method_id         UUID PRIMARY KEY REFERENCES master_mfa_methods(id),
    secret_encrypted  TEXT NOT NULL,
    last_code_hash    VARCHAR(64),              -- anti-replay (SHA-256 do código)
    failed_attempts   INT NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ
);

CREATE TABLE master_recovery_codes (
    id         UUID PRIMARY KEY,
    method_id  UUID NOT NULL REFERENCES master_mfa_methods(id),
    code_hash  VARCHAR(64) NOT NULL,            -- SHA-256; single-use
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_master_recovery_code UNIQUE (method_id, code_hash)
);

CREATE INDEX ix_master_recovery_codes_method ON master_recovery_codes(method_id);
