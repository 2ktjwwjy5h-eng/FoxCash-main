# Spec 27 — Task breakdown

Status: `audited` (2026-08-04). Ciclo SDD completo — implementada (commit `1bbc06b`) e auditada.

## Implementação

- [x] Migration `V17__wallet_ledger.sql`: tabela `wallet_ledger` (colunas,
      índices `ix_wallet_ledger_merchant_created`/`ix_wallet_ledger_ref` e
      unique `uq_wallet_ledger_ref` **incluindo `entry_type`**:
      `(merchant_id, entry_type, reference_type, reference_id)` — a mesma
      transação gera `SALE_CREDIT` e `REFUND_DEBIT`, então o tipo faz parte da
      chave).
- [x] `WalletLedgerEntryType` (enum) + `WalletLedgerEntry` (record imutável).
- [x] `WalletLedgerRepository` (port out): `save`, `existsByMerchantId`,
      `findByMerchantId` (paginado, filtros type/from/to), `sumAmountByMerchantId`,
      `findLatestByMerchantId`.
- [x] `WalletLedgerJpaEntity` + `WalletLedgerMapper` + `WalletLedgerJpaRepository`
      + `WalletLedgerPersistenceAdapter`.
- [x] `WalletLedgerRecorder` (application): grava entry com
      `balanceAfter = merchant.walletBalance()` pós-mutação; chamado dentro do
      `@Transactional` dos fluxos.
- [x] Integrar recorder nos 4 fluxos: `ProcessPixWebhookService` (SALE_CREDIT/
      REFUND_DEBIT), `RegisterChargebackService` (CHARGEBACK_DEBIT),
      `WithdrawalService` (WITHDRAWAL_DEBIT).
- [x] `OpeningBalanceSeedRunner`: `OPENING_BALANCE` = saldo atual por lojista,
      idempotente (re-boot não duplica).
- [x] `QueryWalletLedgerUseCase` + `QueryWalletLedgerService` (paginado, 400
      `invalid_argument` via `QueryParamParser`).
- [x] `WalletLedgerController`: `GET /api/v1/master/merchants/{id}/ledger`
      (ROLE_MASTER) + `GET /api/v1/portal/wallet/ledger` (ROLE_MERCHANT_PORTAL,
      só o próprio) + DTO `WalletLedgerEntryResponse`.

## Verificação

- [x] `WalletLedgerRecorderTest`: cada tipo grava 1 entry com `balance_after`
      correto; rejeita referência duplicada (constraint/backstop).
- [x] `QueryWalletLedgerServiceTest`: paginação, filtros, 400, 403 (outro
      lojista no portal).
- [x] Ajustar `ProcessPixWebhookServiceTest`/`RegisterChargebackServiceTest`/
      `WithdrawalServiceTest` para verificar a entry no ledger.
- [x] E2E: cenário 11 (Ledger do wallet) verificando ledger após
      webhook/chargeback/saque; suite **13/13** sem regressão.
- [x] `mvn test` verde (**246 testes, 0 falhas** — 204 + 42 novos das specs
      27/31/32).
- [x] Roadmap `created` → `verified` → `implemented`; requirements/design/task
      atualizados com contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
