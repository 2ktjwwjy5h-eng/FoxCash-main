-- Spec 14: saidas (saques) do wallet do lojista + controle master de bloqueio.
-- O saque e automatico: debita wallet_balance e registra a saida COMPLETED na
-- mesma transacao. O bloqueio (withdrawals_blocked) impede novos saques.
CREATE TABLE withdrawals (
    id           UUID PRIMARY KEY,
    merchant_id  UUID          NOT NULL REFERENCES merchants(id),
    amount       NUMERIC(15,2) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    created_at   TIMESTAMP     NOT NULL,
    completed_at TIMESTAMP,
    rejected_at  TIMESTAMP
);

CREATE INDEX ix_withdrawals_merchant_id ON withdrawals(merchant_id);
CREATE INDEX ix_withdrawals_status      ON withdrawals(status);
CREATE INDEX ix_withdrawals_created_at  ON withdrawals(created_at);

ALTER TABLE merchants ADD COLUMN withdrawals_blocked BOOLEAN NOT NULL DEFAULT FALSE;
