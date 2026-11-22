-- Spec 120: PVS (Pagamento via Saldo) — novo tipo de saída BILL_PAYMENT.
-- DESVIO de numeração (registrado no roadmap/task): o design previa V68, mas as
-- V69/V70 (Specs 122/124 — perfis de operação) já foram aplicadas; Flyway sem
-- out-of-order não aplica versão menor. PVS usa V71 (próximo livre).
--
-- 1) A V21 criou withdrawals.type SEM CHECK (só o enum Java restringia).
--    Documenta os valores no banco (dados existentes: MERCHANT/ADMINISTRATIVE;
--    BILL_PAYMENT é o novo valor — 12 chars <= VARCHAR(20)).
ALTER TABLE withdrawals
    ADD CONSTRAINT ck_withdrawals_type
    CHECK (type IN ('MERCHANT', 'ADMINISTRATIVE', 'BILL_PAYMENT'));

-- 2) Teto diário do PVS (Fase A sob lock): soma de amount+fee dos BILL_PAYMENT
--    criados no dia por lojista (todas as tentativas, qualquer status).
CREATE INDEX ix_withdrawals_merchant_type_created
    ON withdrawals(merchant_id, type, created_at);
