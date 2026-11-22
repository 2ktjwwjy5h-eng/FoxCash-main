-- Spec 88: taxa de saque (R$ 2,50) — fee_amount persistido no saque para
-- reversão determinística no FAILED (imune a mudança de config). Default 0.00
-- cobre saques administrativos (isentos) e dados legados.
ALTER TABLE withdrawals
    ADD COLUMN fee_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00;
