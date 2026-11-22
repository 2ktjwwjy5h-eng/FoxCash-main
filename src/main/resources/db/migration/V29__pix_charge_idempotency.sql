-- Spec 28: idempotência na criação de cobrança.
-- A chave é do cliente (bot/portal), única POR LOJISTA. O índice único parcial
-- garante que o retry com a mesma chave nunca duplica a transação; transações
-- históricas (sem chave) não colidem.
ALTER TABLE pix_transactions ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX uq_pix_transactions_idempotency
    ON pix_transactions(merchant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
