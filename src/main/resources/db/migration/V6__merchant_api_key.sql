-- Spec 12: autenticacao da API por API key.
-- A chave em claro nunca e persistida — apenas o hash SHA-256 (coluna indexada,
-- mesmo padrao do document_hash, ADR-004). Lojistas existentes ficam com NULL
-- (sem chave) ate re-cadastro/rotacao.
ALTER TABLE merchants ADD COLUMN api_key_hash VARCHAR(64);
CREATE UNIQUE INDEX uq_merchants_api_key_hash ON merchants(api_key_hash);
