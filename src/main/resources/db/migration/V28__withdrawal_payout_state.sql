-- Spec 36: saque via PSP (PIX de saida) — estado real do saque.
-- O debito continua imediato na solicitacao (decisao 2026-08-04); o PSP
-- processa em seguida e o webhook de saida/reconciliacao confirma COMPLETED
-- ou FAILED (estorno automatico do debito ao saldo).

ALTER TABLE withdrawals
    ADD COLUMN external_reference VARCHAR(100),
    ADD COLUMN payout_provider    VARCHAR(30),   -- MOCK | (futuro) PAGARME | ASAAS ...
    ADD COLUMN failure_reason     VARCHAR(255),
    ADD COLUMN failed_at          TIMESTAMP;

-- Reconciaciacao: PROCESSING orfaos por (status, created_at).
CREATE INDEX ix_withdrawals_status_created ON withdrawals(status, created_at);
