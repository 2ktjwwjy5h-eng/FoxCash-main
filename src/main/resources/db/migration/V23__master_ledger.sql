-- Spec 34: Ledger do master — receita/perda da plataforma registrada na mesma
-- transacao do evento financeiro que a originou (mesma filosofia da Spec 27).

-- master_ledger: movimentacao financeira da plataforma por lojista.
--   entry_type: TRANSACTION_FEE (0,50 x vendas - fatura paga) | MONTHLY_FEE
--               (69,95 - fatura paga) | RECEIVABLE_RECOVERY (receivable de
--               estorno pago) | ADMIN_RECOVERY (saque administrativo) |
--               CHARGEBACK_LOSS (baixa/write-off de receivable).
--   reference_id: invoice.id / receivable.id / withdrawal.id (conforme origem).
--   balance_after: saldo da plataforma para o lojista apos a entry (soma
--                  cumulativa por lojista, serializada pelo lock do merchant).
CREATE TABLE master_ledger (
    id             UUID PRIMARY KEY,
    merchant_id    UUID NOT NULL REFERENCES merchants(id),
    entry_type     VARCHAR(20) NOT NULL,
    amount         NUMERIC(15,2) NOT NULL,
    balance_after  NUMERIC(15,2) NOT NULL,
    reference_type VARCHAR(20),
    reference_id   UUID,
    created_at     TIMESTAMP NOT NULL
);

CREATE INDEX ix_master_ledger_merchant_created ON master_ledger(merchant_id, created_at);
CREATE INDEX ix_master_ledger_ref ON master_ledger(reference_type, reference_id);

-- Idempotencia por origem: cada evento gera UMA entry por TIPO. O entry_type
-- faz parte da chave porque uma fatura paga gera TRANSACTION_FEE + MONTHLY_FEE
-- com a mesma referencia (invoice.id).
CREATE UNIQUE INDEX uq_master_ledger_ref
    ON master_ledger(merchant_id, entry_type, reference_type, reference_id);
