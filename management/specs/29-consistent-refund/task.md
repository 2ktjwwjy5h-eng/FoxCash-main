# Spec 29 — Task breakdown

Status: `created` (2026-08-04). Implementação pendente — checklist abaixo.

## Implementação

- [x] `ChargebackAlreadyExistsException` (domain) + handler → 409
      `chargeback_already_exists`.
- [x] `TransactionRepository`/`PixTransactionJpaRepository`/adapter:
      `findByIdForUpdate(UUID id)` (lock pessimista na transação).
- [x] `RegisterChargebackService.register`: lock na transação; checagens
      (posse → duplicado 409 → APPROVED 409) sob lock; `transaction.refund()` +
      save; débito 1× + `WalletLedgerRecorder` (CHARGEBACK_DEBIT); catch da
      unique como backstop → 409.
- [x] `ProcessPixWebhookService.process`: `applyIf(expected, effect)` — transição
      só se o estado atual for o esperado; senão **no-op + log de alerta**
      (replay e fora de ordem); credit/debit apenas em transição válida +
      recorder (SALE_CREDIT/REFUND_DEBIT).
- [x] Confirmar que `PixTransaction.refund()` (APPROVED → REFUNDED) é reutilizada
      no chargeback sem conflito com o webhook (estado serializado por lock).

## Verificação

- [x] `RegisterChargebackServiceTest`: chargeback → transação REFUNDED + débito
      1× + entry CHARGEBACK_DEBIT; duplicado (sequencial e concorrente) → 409
      `chargeback_already_exists` sem 2º débito; transação não-APPROVED → 409
      `invalid_state`.
- [x] `ProcessPixWebhookServiceTest`: REFUNDED pós-chargeback → no-op (sem 2º
      débito); REFUNDED de APPROVED → débito 1× + entry REFUND_DEBIT; fora de
      ordem (REFUNDED de PENDING, APPROVED de REFUNDED, PENDING de APPROVED) →
      no-op (sem exceção); replay PAID/REFUNDED → no-op; status desconhecido →
      400.
- [x] E2E: cenário de chargeback → transação REFUNDED e webhook REFUNDED
      pós-chargeback não debita de novo (saldo estável); suite **10/10** sem
      regressão.
- [x] `mvn test` verde (204 + novos).
- [x] Roadmap `created` → `verified`; requirements/design/task atualizados com
      contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
