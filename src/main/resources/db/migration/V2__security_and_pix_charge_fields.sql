-- ============================================================
-- NyxCash - Spec 4: seguranca (cripto) + cobranca Pix
-- 1) merchants.document passa a ser criptografado (AES-256/GCM)
--    -> coluna VARCHAR(255); UNIQUE migra para document_hash (SHA-256).
-- 2) merchants.wallet_balance (saldo do lojista).
-- 3) pix_transactions: external_reference, payer_document (cripto),
--    pix_qr_code, pix_copy_paste; pix_key passa a ser opcional.
-- ============================================================

-- ---- merchants ----
ALTER TABLE merchants ALTER COLUMN document TYPE VARCHAR(255);

-- UNIQUE sobre o documento cifrado nao faz sentido (IV aleatorio);
-- a unicidade passa a ser garantida por document_hash (SHA-256).
ALTER TABLE merchants DROP CONSTRAINT merchants_document_key;

ALTER TABLE merchants ADD COLUMN document_hash VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX uq_merchants_document_hash ON merchants(document_hash);

ALTER TABLE merchants ADD COLUMN wallet_balance NUMERIC(15,2) NOT NULL DEFAULT 0.00;

-- ---- pix_transactions ----
ALTER TABLE pix_transactions ADD COLUMN external_reference VARCHAR(255);
ALTER TABLE pix_transactions ADD COLUMN payer_document VARCHAR(255);
ALTER TABLE pix_transactions ADD COLUMN pix_qr_code TEXT;
ALTER TABLE pix_transactions ADD COLUMN pix_copy_paste TEXT;

-- Pix Copia e Cola nao exige pix_key (chave pode vir embutida no BR Code)
ALTER TABLE pix_transactions ALTER COLUMN pix_key DROP NOT NULL;

CREATE UNIQUE INDEX uq_pix_transactions_external_reference
    ON pix_transactions(external_reference) WHERE external_reference IS NOT NULL;
