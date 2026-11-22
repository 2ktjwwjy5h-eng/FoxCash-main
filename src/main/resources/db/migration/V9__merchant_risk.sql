-- Spec 16 (ADR-008): snapshots de risco por lojista, calculados em job
-- agendado sobre os dados existentes (transacoes). A decisao (suspender/
-- liberar) e do master. Chargeback/denuncias entram 0 (sem fonte de dados
-- modelada nesta versao); colunas prontas para alimentacao futura.
CREATE TABLE merchant_risk (
    id                      UUID PRIMARY KEY,
    merchant_id             UUID          NOT NULL REFERENCES merchants(id),
    computed_at             TIMESTAMP     NOT NULL,
    approved_volume         NUMERIC(15,2) NOT NULL,
    total_transactions      BIGINT        NOT NULL,
    approved_transactions   BIGINT        NOT NULL,
    refunded_transactions   BIGINT        NOT NULL,
    refund_rate_percent     NUMERIC(6,2)  NOT NULL,
    chargeback_rate_percent NUMERIC(6,2)  NOT NULL DEFAULT 0,
    complaints_count        INT           NOT NULL DEFAULT 0,
    refund_alert            BOOLEAN       NOT NULL DEFAULT FALSE,
    chargeback_alert        BOOLEAN       NOT NULL DEFAULT FALSE,
    complaints_alert        BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE INDEX ix_merchant_risk_merchant_id ON merchant_risk(merchant_id);
CREATE INDEX ix_merchant_risk_computed_at ON merchant_risk(computed_at);
