-- ============================================================
-- NyxCash - Schema inicial
-- Modelo de precificacao fixa: R$ 0,50 por transacao + R$ 69,95 mensais
-- ============================================================

CREATE TABLE merchants (
    id               UUID PRIMARY KEY,
    trading_name     VARCHAR(120) NOT NULL,
    legal_name       VARCHAR(120) NOT NULL,
    document         VARCHAR(20)  NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL,
    pricing_fee_per_transaction NUMERIC(15,2) NOT NULL DEFAULT 0.50,
    pricing_monthly_fee         NUMERIC(15,2) NOT NULL DEFAULT 69.95,
    created_at       TIMESTAMP  NOT NULL
);

CREATE TABLE pix_transactions (
    id            UUID PRIMARY KEY,
    merchant_id   UUID NOT NULL REFERENCES merchants(id),
    amount        NUMERIC(15,2) NOT NULL,
    description   VARCHAR(255),
    pix_key       VARCHAR(255) NOT NULL,
    end_to_end_id VARCHAR(64),
    status        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    paid_at       TIMESTAMP
);

CREATE INDEX idx_pix_transactions_merchant ON pix_transactions(merchant_id, created_at);
CREATE UNIQUE INDEX uq_pix_transactions_end_to_end ON pix_transactions(end_to_end_id) WHERE end_to_end_id IS NOT NULL;

CREATE TABLE invoices (
    id                    UUID PRIMARY KEY,
    merchant_id           UUID NOT NULL REFERENCES merchants(id),
    billing_period        VARCHAR(7) NOT NULL,
    transaction_count     INTEGER NOT NULL DEFAULT 0,
    transaction_fees_total NUMERIC(15,2) NOT NULL,
    monthly_fee           NUMERIC(15,2) NOT NULL,
    total_amount          NUMERIC(15,2) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    due_date              DATE NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    CONSTRAINT uq_invoices_merchant_period UNIQUE (merchant_id, billing_period)
);
