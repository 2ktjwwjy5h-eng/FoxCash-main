-- Antifraude real (Spec 19, 2026-08-04): deteccao de chargeback via buyerId
-- opaco anonimo + registro de chargebacks.
--
-- buyer_id_hash: pseudonimo opaco do comprador enviado pelo BOT do lojista
-- (SEM PII — nome/telefone/CPF nunca trafegam nem sao armazenados). Apenas o
-- hash SHA-256 e persistido, seguindo o padrao do projeto (document_hash,
-- api_key_hash, token_hash). Usado para velocidade por comprador e bloqueio
-- de comprador com historico de chargeback.
ALTER TABLE pix_transactions ADD COLUMN buyer_id_hash VARCHAR(64);
CREATE INDEX idx_pix_transactions_buyer_created ON pix_transactions (buyer_id_hash, created_at);

-- Chargebacks: disputas registradas contra transacoes (fonte de dados do
-- indicador de chargeback da Spec 16 — antes sempre 0).
CREATE TABLE chargebacks (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    buyer_id_hash VARCHAR(64),
    amount NUMERIC(15, 2) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_chargebacks_transaction UNIQUE (transaction_id)
);
CREATE INDEX idx_chargebacks_merchant_created ON chargebacks (merchant_id, created_at);
CREATE INDEX idx_chargebacks_buyer_created ON chargebacks (buyer_id_hash, created_at);

-- Score de risco (0-100) derivado das regras — Spec 19.
ALTER TABLE merchant_risk ADD COLUMN risk_score INT NOT NULL DEFAULT 0;
