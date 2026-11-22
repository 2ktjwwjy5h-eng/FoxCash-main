-- Spec 20 (auditoria 2026-08-04): registra o último uso da credencial do portal
-- para auditoria. Null quando o lojista nunca logou no portal.
ALTER TABLE merchant_credentials ADD COLUMN last_used_at TIMESTAMP;
