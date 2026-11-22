# Spec 88 — Task breakdown

Status: `audited` (2026-08-13). Ciclo SDD completo.

## Implementação

- [x] **Config** — `PricingProperties.withdrawalFee` (default `2.50`) +
      getter/setter; `application.yml` (`foxcash.pricing.withdrawal-fee: 2.50`).
- [x] **Migration V51** — `ALTER TABLE withdrawals ADD COLUMN fee_amount
      NUMERIC(15,2) NOT NULL DEFAULT 0.00;`
- [x] **Migration V52** — alarga `entry_type` para `VARCHAR(30)` em
      `wallet_ledger` e `master_ledger` (achado de auditoria: ver §Auditoria).
- [x] **Domínio `Withdrawal`** — campo imutável `Money fee`: `processing(...)`
      com `fee` (novo parâmetro), `restore(...)` com `fee`, `fee()` accessor;
      transições `submitToPsp`/`complete`/`fail` preservam o campo.
- [x] **Persistência** — `WithdrawalJpaEntity` (+ `fee_amount` column + getter) e
      mapper (salvar/restaurar `fee`).
- [x] **Ledgers** — `WalletLedgerEntryType` += `WITHDRAWAL_FEE_DEBIT`/
      `WITHDRAWAL_FEE_REVERSAL`; `MasterLedgerEntryType` += `WITHDRAWAL_FEE`/
      `WITHDRAWAL_FEE_REVERSAL`.
- [x] **`WithdrawalProcessingPersister`** — `createMerchantWithdrawal` e
      `createMerchantAutoWithdrawal` ganham `Money fee`; manual:
      `withdraw(amount.add(fee))` + ledger duplo + master `WITHDRAWAL_FEE`;
      automático: `amount = saldo − fee` (saldo ≤ fee → skip) + mesmo fluxo;
      administrativo: `fee = 0.00`, inalterado.
- [x] **`WithdrawalService`** — injeta `PricingProperties` e repassa a taxa.
- [x] **Job de auto-withdraw (Spec 70)** — injeta `PricingProperties` e repassa
      a taxa ao persister.
- [x] **`WithdrawalSettlementService`** — FAILED: `credit(amount + fee)` + ledger
      duplo (`WITHDRAWAL_REVERSAL` + `WITHDRAWAL_FEE_REVERSAL`) + master
      `WITHDRAWAL_FEE_REVERSAL` (se fee > 0); admin inalterado (SEC-29).
- [x] **Receita do master** — `RevenueSummary` += `withdrawalFees`;
      `MasterRevenueService.summary` soma `WITHDRAWAL_FEE` −
      `WITHDRAWAL_FEE_REVERSAL` e inclui no `total`/`net`;
      `RevenueSummaryResponse` += `withdrawalFees`.
- [x] **Resposta** — `WithdrawalResponse` += `fee` (aditivo).
- [x] **Testes unitários** — `WithdrawalTest` (fee preservada nas transições),
      `WithdrawalProcessingPersisterTest` (débito valor+taxa, entries,
      auto saca saldo−taxa, admin isento), `WithdrawalSettlementServiceTest`
      (estorno valor+taxa, reversão de receita no master, admin SEC-29),
      `WithdrawalServiceTest` (repasse da taxa), `AutoWithdrawRunnerServiceTest`,
      `MasterWithdrawalServiceTest` (admin isento), `MasterRevenueServiceTest`
      (`withdrawalFees` no summary).
- [x] **E2E** — journey 13/14/16/17/18 (saldos com taxa + `withdrawalFees` 7,50),
      psp-flow (117,50), reliability (117,50/617,50), auto-withdraw (247,50 +
      `fee` 2,50), master-console-stepup (admin sem taxa).

## Verificação (`/spec-verify 88`)

- [x] Consistência dos docs contra o código real (config, enums, persister,
      settlement, revenue, response, migrations V51/V52) — sem achados.

## Auditoria (`/spec-audit 88`)

**10 critérios do requirements verificados um a um; 1 achado de código
corrigido; 0 abertos.**

1. **Config** — `foxcash.pricing.withdrawal-fee: 2.50` (`application.yml`) +
   `PricingProperties.withdrawalFee` (default 2.50), injetado no manual
   (`WithdrawalService`) e automático (`AutoWithdrawRunnerService`). ✅
2. **Débito total no create** — `merchant.withdraw(amount.add(fee))` (saldo ≥
   valor + taxa → senão 409 `insufficient_balance`); `WITHDRAWAL_DEBIT` +
   `WITHDRAWAL_FEE_DEBIT` na mesma transação. ✅
3. **Saque automático** — `amount = saldo − 2,50` (wallet zera); saldo ≤ taxa →
   `AutoWithdrawBelowThresholdException` (skip silencioso). E2E: auto-withdraw
   amount 247,50 + fee 2,50 + saldo 0. ✅
4. **Admin isento** — `createAdministrativeWithdrawal` com `fee = 0.00`, sem
   ledger de taxa; E2E stepup cenário 5 verde. ✅
5. **Taxa persistida** — `withdrawals.fee_amount` (V51) — reversão determinística
   (imune a mudança de config). ✅
6. **Estorno devolve tudo** — FAILED credita `valor + taxa`, registra
   `WITHDRAWAL_REVERSAL` + `WITHDRAWAL_FEE_REVERSAL`; idempotente (gate
   PROCESSING + lock). E2E journey 17 verde (saldo restaurado ao `balanceBefore`).
   ✅
7. **Receita no master** — `WITHDRAWAL_FEE` na Fase A; `WITHDRAWAL_FEE_REVERSAL`
   no FAILED (padrão SEC-29); summary expõe `withdrawalFees` (Σ fee − Σ reversão)
   e inclui no `total`/`net`. E2E journey 18.7: `withdrawalFees` = 7,50
   (4 saques × 2,50 − 1 reversão). ✅
8. **Resposta** — `WithdrawalResponse.fee` (aditivo); E2E auto-withdraw valida
   `auto.fee = 2,50`. ✅
9. **Testes** — `mvn test` **1465/0/0** BUILD SUCCESS; E2E: journey **27/27**,
   psp-flow + reliability + auto-withdraw **5/5**, master-console-stepup **5/5**.
   ✅
10. **Documentação** — README (precificação) e `docs/politicas-uso.md` (§3.9)
    citam a taxa de saque. ✅

**Achado corrigido (bug latente pré-existente, V52):** ao gravar
`WITHDRAWAL_FEE_REVERSAL` (23 caracteres) no estorno de saque FAILED, o Postgres
rejeitou com `SQLState 22001` — a coluna `wallet_ledger.entry_type` era
`VARCHAR(20)`. A inspeção revelou que `master_ledger.entry_type` era igual
(`VARCHAR(20)`) e que **`ADMIN_RECOVERY_REVERSAL` (23 chars, SEC-29) já era um
bug latente na MESMA coluna** — qualquer saque administrativo FAILED real
quebraria com 500 (nunca exercitado em E2E porque nenhum teste falha saque
administrativo). Corrigido alargando `entry_type` para `VARCHAR(30)` nas DUAS
tabelas (V52). Registro no `management/roadmap.md` (Spec 88 §Auditoria).

**0 achados abertos na Spec 88.** Fim do ciclo SDD
(roadmap/requirements/design/task `audited`).
