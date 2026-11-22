-- Auditoria de homologacao 2026-08-05 (B-08): lockout de login do perfil master.
-- Apos max-login-attempts falhas seguidas (default 5), o master fica bloqueado
-- por lockout-minutes (default 15) — combate forca bruta / credential stuffing
-- no login do operador. Colunas com default preservam linhas existentes.
ALTER TABLE masters ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE masters ADD COLUMN locked_until TIMESTAMP;
