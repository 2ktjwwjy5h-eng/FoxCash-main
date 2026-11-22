-- Spec 141 — Device fingerprint (caseiro) do portal/cadastro do lojista.
--
-- Sinal de risco passivo: o frontend coleta sinais do navegador (canvas,
-- WebGL, fontes, UA, timezone, etc.), normaliza e reduz a um hash SHA-256
-- (64 hex) — enviado como campo OPCIONAL no cadastro (POST /api/v1/merchants)
-- e no login (POST /api/v1/merchant-auth/login). O backend valida o formato,
-- grava o hash e a associação com o lojista. NUNCA bloqueia: o fingerprint é
-- um sinal de auditoria/risco (Spec 141), a decisão é do master.
--
-- Nenhuma tabela usa prefixo nyx_ (rebrand Spec 140); credenciais do banco
-- continuam em infra (nome nyxcash).

-- Hash único do dispositivo (SHA-256 hex, 64 chars) + janelas de uso.
CREATE TABLE device_fingerprints (
    fingerprint_hash  VARCHAR(64)   PRIMARY KEY,
    first_seen_at     TIMESTAMPTZ   NOT NULL,
    last_seen_at      TIMESTAMPTZ   NOT NULL,
    seen_count        BIGINT        NOT NULL DEFAULT 1,
    distinct_merchants INTEGER      NOT NULL DEFAULT 1
);

-- Associação hash -> lojista (1 dispositivo em N contas = sinal de risco).
CREATE TABLE device_fingerprint_merchants (
    fingerprint_hash  VARCHAR(64)   NOT NULL REFERENCES device_fingerprints(fingerprint_hash) ON DELETE CASCADE,
    merchant_id       UUID          NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    first_seen_at     TIMESTAMPTZ   NOT NULL,
    last_seen_at      TIMESTAMPTZ   NOT NULL,
    seen_count        BIGINT        NOT NULL DEFAULT 1,
    PRIMARY KEY (fingerprint_hash, merchant_id)
);

-- Consulta de risco: quantos lojistas distintos um dispositivo já tocou.
CREATE INDEX ix_device_fingerprint_merchants_merchant ON device_fingerprint_merchants(merchant_id);
