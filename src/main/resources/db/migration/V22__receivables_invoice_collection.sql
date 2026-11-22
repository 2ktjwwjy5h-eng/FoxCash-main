-- Spec 33: Faturamento automatico e contas a receber — cobranca da fatura no
-- wallet no vencimento (total/parcial), divida formal (receivables) e bloqueio
-- de saque por inadimplencia.

-- receivables: divida formal do lojista com a plataforma.
--   origin: INVOICE (fatura nao coberta) | CHARGEBACK | REFUND (estorno que
--           deixou o saldo negativo) | ADJUSTMENT (reservado).
--   reference_id: invoice.id / chargeback.id / transaction.id (conforme origin).
--   amount: valor devedor total (positivo).
--   paid_amount: valor ja pago; status OPEN (nada pago) | PARTIAL (parcial) | PAID.
CREATE TABLE receivables (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    origin       VARCHAR(20) NOT NULL,
    reference_id UUID NOT NULL,
    amount       NUMERIC(15,2) NOT NULL,
    paid_amount  NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    paid_at      TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL
);

CREATE INDEX ix_receivables_merchant ON receivables(merchant_id, status);

-- Idempotencia: uma divida por origem (backstop contra corrida).
CREATE UNIQUE INDEX uq_receivables_origin ON receivables(merchant_id, origin, reference_id);

-- invoices: rastreabilidade da cobranca (Spec 33).
--   paid_amount: total pago ate o momento (pagamento parcial nao muda o status
--                para PAID ate quitar; o status e controlado pelo dominio).
--   collected_at: data da ultima cobranca processada.
ALTER TABLE invoices
    ADD COLUMN paid_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN collected_at TIMESTAMP;
