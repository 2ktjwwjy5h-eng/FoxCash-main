-- Spec 58: índice para o cap de cobranças PENDENTES por lojista
-- (countByMerchantIdAndStatus — criação de cobrança com teto de pendentes).
CREATE INDEX ix_pix_transactions_merchant_status
    ON pix_transactions (merchant_id, status);
