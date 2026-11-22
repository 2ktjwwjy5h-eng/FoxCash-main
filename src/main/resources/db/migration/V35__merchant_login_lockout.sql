-- Auditoria de homologacao 2026-08-05 (B-06): lockout por conta no login do
-- lojista (portal). Apos max-login-attempts falhas seguidas (default 5), a
-- credencial fica bloqueada por lockout-minutes (default 15) — combate forca
-- bruta / credential stuffing no login do lojista, mesmo padrao do master
-- (B-08, V34). Colunas com default preservam linhas existentes.
ALTER TABLE merchant_credentials ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE merchant_credentials ADD COLUMN locked_until TIMESTAMP;
