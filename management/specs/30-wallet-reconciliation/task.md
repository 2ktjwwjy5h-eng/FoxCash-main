# Spec 30 — Task breakdown

Status: `created` (2026-08-04). Implementação pendente — checklist abaixo.

## Implementação

- [x] Migration `V30__reconciliation_issues.sql`: tabela `reconciliation_issues`
      + índice `(merchant_id, status)` + unique parcial `uq_reconciliation_open`
      (`merchant_id` onde `status='OPEN'`).
- [x] `ReconciliationIssue` (domain) + `ReconciliationIssueRepository` (port
      out: `open` idempotente via `ON CONFLICT`/checagem, `findByMerchantId`
      paginado) + adapter JPA + entidade/mapper.
- [x] `WalletReconciliationUseCase` + `WalletReconciliationService`: `run()`
      valida todos os lojistas (`findLatestByMerchantId` do ledger vs
      `wallet_balance`); registra issue `OPEN` por divergência (idempotente);
      conta `orphanCharges` (transações PENDING sem externalReference).
- [x] `WalletReconciliationScheduler` (ShedLock `foxcash-wallet-reconciliation`,
      cron `foxcash.scheduler.reconciliation-cron`, default diário 04:00 UTC).
- [x] `ReconciliationController` (ROLE_MASTER): `POST
      /api/v1/master/reconciliation/run` + `GET
      /api/v1/master/reconciliation/issues` (paginado, filtros
      `merchantId`/`status`, 400 `invalid_argument` via `QueryParamParser`) +
      DTOs.
- [x] `GenerateInvoiceService`: contagem da fatura via
      `countByMerchantIdAndStatusAndCreatedAtBetween(APPROVED)` (COUNT, não
      `.size()`); `InvoiceCalculator.draft` aceita `long`.
- [x] Escala 2 no JSON: helper de normalização (`setScale(2, HALF_EVEN)`)
      aplicado nos DTOs financeiros (portal sales/wallet/dashboard, master
      dashboard, ledger).

## Verificação

- [x] `WalletReconciliationServiceTest`: lojista consistente → sem issue;
      divergência simulada → 1 issue OPEN (valores expected/actual); re-execução
      não duplica; `orphanCharges` contabilizadas.
- [x] `ReconciliationControllerTest`: run 200 (resumo), issues paginado,
      400/401/403.
- [x] `GenerateInvoiceServiceTest`: fatura idêntica à atual usando COUNT
      (mock verifica `countByMerchantIdAndStatusAndCreatedAtBetween`, não
      `findApprovedByMerchantIdBetween`).
- [x] Teste de escala: `volume30d`/`balance` serializam `150.00` (não `150.0`).
- [x] E2E: `POST /api/v1/master/reconciliation/run` após chargeback/saque →
      resumo consistente; suite **10/10** sem regressão.
- [x] `mvn test` verde (204 + novos).
- [x] Roadmap `created` → `verified`; requirements/design/task atualizados com
      contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
