-- Índices de apoio às consultas do dashboard/admin (Spec 8):
-- listagens filtráveis por status/período e resumo agregado.
CREATE INDEX ix_merchants_status ON merchants(status);
CREATE INDEX ix_invoices_status ON invoices(status);
CREATE INDEX ix_invoices_billing_period ON invoices(billing_period);
