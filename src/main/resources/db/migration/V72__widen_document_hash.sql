-- Spec 97/99 (painel) expôs um defeito latente do documento do lojista (B-A1):
-- DocumentHasher.hmac gera "h1:" + 64 hex = 67 chars, mas document_hash era
-- VARCHAR(64) — qualquer save do lojista com documento (ex.: crédito de wallet
-- no webhook PAID) estourava o UPDATE → 500. Alarga para 80 (folga de prefixo).
ALTER TABLE merchants ALTER COLUMN document_hash TYPE VARCHAR(80);
