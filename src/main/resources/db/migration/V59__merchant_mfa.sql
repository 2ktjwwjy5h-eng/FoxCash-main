-- Spec 94 — MFA 2FA TOTP para o lojista no portal (auditoria de segurança
-- 2026-08-14, lacuna #1 — o master já tem MFA na Spec 44, o lojista não).
-- Modelagem espelho das V41/V42 do master, preparada para múltiplos métodos:
-- merchant_mfa_methods = registro (type/status); merchant_totp = detalhe TOTP;
-- merchant_recovery_codes = códigos de recuperação single-use (SHA-256).
--
-- DIFERENÇA vs master (lição V42): as FKs de merchant_totp e
-- merchant_recovery_codes já nascem com ON DELETE CASCADE — o deleteByMerchantId
-- (desativação e descarte de método PENDING) remove os detalhes em cascata sem
-- violar FK (o master precisou da V42 de correção; aqui não).

CREATE TABLE merchant_mfa_methods (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    type         VARCHAR(20) NOT NULL,          -- 'TOTP' (futuro: 'WEBAUTHN')
    status       VARCHAR(20) NOT NULL,          -- PENDING | ACTIVE | DISABLED
    created_at   TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ                    -- TTL do estado PENDING (10min)
);

CREATE INDEX ix_merchant_mfa_methods_merchant ON merchant_mfa_methods(merchant_id);

-- Detalhe TOTP: secret SEMPRE criptografado em repouso (CryptoConverter,
-- AES-256/GCM, formato v1:) — o servidor precisa do valor original para
-- recalcular o código esperado (hash puro tornaria a verificação impossível).
CREATE TABLE merchant_totp (
    method_id         UUID PRIMARY KEY REFERENCES merchant_mfa_methods(id) ON DELETE CASCADE,
    secret_encrypted  TEXT NOT NULL,
    last_code_hash    VARCHAR(64),              -- anti-replay (SHA-256 do código)
    failed_attempts   INT NOT NULL DEFAULT 0,
    locked_until      TIMESTAMPTZ
);

CREATE TABLE merchant_recovery_codes (
    id         UUID PRIMARY KEY,
    method_id  UUID NOT NULL REFERENCES merchant_mfa_methods(id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,            -- SHA-256; single-use
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_merchant_recovery_code UNIQUE (method_id, code_hash)
);

CREATE INDEX ix_merchant_recovery_codes_method ON merchant_recovery_codes(method_id);
