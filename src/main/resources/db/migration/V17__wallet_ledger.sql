-- Spec 27: ledger do wallet — movimentações financeiras imutáveis e auditáveis.
-- Toda mutação do saldo (webhook PAID/REFUNDED, chargeback, saque) grava UMA
-- entry aqui, na MESMA transação do efeito financeiro, com balance_after (saldo
-- resultante). O saldo passa a ser validável contra o histórico (Spec 30).
--
-- Imutável: nenhum UPDATE/DELETE pela aplicação — apenas INSERT.
-- Idempotência por origem: a unique inclui entry_type porque a MESMA origem
-- pode gerar entries de tipos diferentes (ex.: transação aprovada → SALE_CREDIT
-- e, se estornada depois, REFUND_DEBIT).
CREATE TABLE wallet_ledger (
    id             UUID PRIMARY KEY,
    merchant_id    UUID          NOT NULL REFERENCES merchants(id),
    entry_type     VARCHAR(20)   NOT NULL,  -- OPENING_BALANCE | SALE_CREDIT |
                                            -- REFUND_DEBIT | CHARGEBACK_DEBIT |
                                            -- WITHDRAWAL_DEBIT
    amount         NUMERIC(15,2) NOT NULL,
    balance_after  NUMERIC(15,2) NOT NULL,
    reference_type VARCHAR(20),             -- TRANSACTION | WITHDRAWAL |
                                            -- CHARGEBACK | NULL (opening)
    reference_id   UUID,
    created_at     TIMESTAMP     NOT NULL
);

CREATE INDEX ix_wallet_ledger_merchant_created ON wallet_ledger(merchant_id, created_at);
CREATE INDEX ix_wallet_ledger_ref ON wallet_ledger(reference_type, reference_id);
CREATE UNIQUE INDEX uq_wallet_ledger_ref
    ON wallet_ledger(merchant_id, entry_type, reference_type, reference_id);
