-- Spec 88: WITHDRAWAL_FEE_REVERSAL (23 chars) estoura entry_type VARCHAR(20) do
-- wallet_ledger no estorno de saque FAILED. Alarga para 30 nas DUAS tabelas de
-- ledger — também corrige ADMIN_RECOVERY_REVERSAL (23) do master_ledger
-- (latente desde SEC-29: nunca exercitado em E2E porque nenhum saque
-- administrativo falha nos testes; qualquer saque admin FAILED real dava 500).
ALTER TABLE wallet_ledger ALTER COLUMN entry_type TYPE VARCHAR(30);
ALTER TABLE master_ledger  ALTER COLUMN entry_type TYPE VARCHAR(30);
