# Spec 33 — Task breakdown

Status: `audited` (2026-08-04). Ciclo SDD completo — implementada e auditada.

## Implementação

- [x] Migration `V22__receivables_invoice_collection.sql`: tabela `receivables`
      (+ índices `(merchant_id,status)` e unique `(merchant_id, origin, reference_id)`)
      e `invoices.paid_amount`/`invoices.collected_at`.
- [x] `ReceivableOrigin`/`ReceivableStatus`/`Receivable` (domínio) +
      `ReceivableRepository` (port out) + adapter JPA (`ReceivablePersistenceAdapter`,
      Specifications).
- [x] `Invoice.applyPayment(Money, Instant)` (paid_amount; só `PAID` quando
      quitada); `InvoiceRepository.findUnpaidDueOnOrBefore(LocalDate)` (PENDING ou
      OVERDUE com saldo devedor) — adaptado para `LocalDate` (due_date é DATE).
- [x] `Merchant.ensureNoOutstandingDebt(boolean)` + flag `outstandingDebt`
      (`markOutstandingDebt`) + `OutstandingDebtException` (409 `outstanding_debt`)
      + handler no `GlobalExceptionHandler`.
- [x] `InvoiceCollectionService` + port in: `collect` (total/parcial/idempotente;
      ledger `INVOICE_FEE_DEBIT` **cumulativo** via Spec 27 — re-cobrança acumula
      na mesma entry, respeitando a `uq_wallet_ledger_ref`; upsert receivable
      restante; re-cobra OVERDUE; marca receivable INVOICE `PAID` ao quitar) e
      `collectDueInvoices` (também quita receivables de estorno
      `CHARGEBACK`/`REFUND` com saldo disponível — `collectReceivables`, entry
      `RECEIVABLE_DEBIT` cumulativa).
- [x] `ReceivableRecorder`: cria receivable `CHARGEBACK`/`REFUND` quando o
      estorno deixa saldo negativo (chamado pela Spec 29 — `RegisterChargebackService`
      e `ProcessPixWebhookService` —, mesma transação; idempotente).
- [x] `InvoiceCollectionScheduler` (ShedLock `foxcash-invoice-collection`, cron
      `foxcash.scheduler.invoice-collection-cron`, default diário 02:30 UTC).
- [x] `WithdrawalService` (vendedor): valida `outstanding_debt` sob lock
      (configurável `foxcash.billing.block-withdrawals-on-debt` via
      `BillingProperties`, default true); saque administrativo do master **não**
      valida (`MasterWithdrawalService` inalterado).
- [x] `ReceivableController` (ROLE_MASTER): `POST
      /merchants/{id}/invoices/{invoiceId}/collect`, `GET /receivables`,
      `GET /merchants/{id}/receivables` + DTOs (`CollectionResponse`,
      `ReceivableResponse`).

## Verificação

- [x] `InvoiceCollectionServiceTest` (9): cobrança total (PAID + ledger + sem
      receivable), parcial (OVERDUE + receivable OPEN com restante), idempotência
      (re-execução no-op), saldo zerado, **re-cobrança de OVERDUE** quando o saldo
      volta, quitação de receivable de estorno, nunca negativo, 404.
- [x] `ReceivableTest` (7): ciclo da dívida (OPEN → PARTIAL → PAID via
      `applyPayment`), origem CHARGEBACK/REFUND por saldo negativo
      (`ReceivableRecorder`), idempotência, no-op com saldo positivo.
- [x] `WalletLedgerRecorderTest` (+2): `recordCumulative` insere na 1ª cobrança e
      acumula na re-cobrança (amount + balance_after; id/created_at intactos).
- [x] `WithdrawalServiceTest` (+3): receivable OPEN → 409 `outstanding_debt`; sem
      dívida → 201; `block-withdrawals-on-debt=false` libera mesmo com dívida.
- [x] E2E: cenário 14 (cobrança total → PAID; parcial → OVERDUE + receivable
      OPEN; bloqueio por dívida 409; saque administrativo do master passa;
      re-cobrança quando o saldo volta → receivable PAID + saque liberado;
      idempotência; ledger INVOICE_FEE_DEBIT) — suite **14/14** sem regressão.
- [x] Fixes do audit: `markPaidIfOpen` agora grava `paid_amount = amount` (o
      receivable INVOICE quitado fica com o valor total pago rastreável);
      `upsertOpen` na re-cobrança parcial acumula `paid_amount` (diferença entre
      o saldo anterior e o restante atual) e marca `PARTIAL` — a dívida formal
      reflete exatamente o pago contra ela. E2E 14.12: `GET
      /api/v1/master/receivables` global paginado com filtros (200), `status`
      inválido → 400 `invalid_argument` e sem auth → 401 (critério 6).
- [x] `mvn test` verde (**267 testes: 246 + 21 novos**).
- [x] Roadmap `verified` → `implemented`; requirements/design/task atualizados com
      contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
