-- Spec 30: reconciliação e integridade do saldo.
-- O job valida wallet_balance contra o ledger (Spec 27) e registra divergências
-- para auditoria do master. Uma issue ABERTA por lojista divergente (o job é
-- idempotente — re-execução não duplica).
CREATE TABLE reconciliation_issues (
    id               UUID PRIMARY KEY,
    merchant_id      UUID NOT NULL REFERENCES merchants(id),
    expected_balance NUMERIC(15,2) NOT NULL,  -- balance_after da última entry do ledger
    actual_balance   NUMERIC(15,2) NOT NULL,  -- merchants.wallet_balance
    status           VARCHAR(20) NOT NULL,    -- OPEN | RESOLVED
    detected_at      TIMESTAMP NOT NULL,
    resolved_at      TIMESTAMP
);

CREATE INDEX ix_reconciliation_issues_merchant
    ON reconciliation_issues(merchant_id, status);

-- Uma issue aberta por lojista divergente (idempotência do job):
CREATE UNIQUE INDEX uq_reconciliation_open
    ON reconciliation_issues(merchant_id)
    WHERE status = 'OPEN';
