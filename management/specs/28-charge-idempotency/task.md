# Spec 28 — Task breakdown

Status: `created` (2026-08-04). Implementação pendente — checklist abaixo.

## Implementação

- [x] Migration `V29__pix_charge_idempotency.sql`: `idempotency_key
      VARCHAR(64)` + índice único parcial `(merchant_id, idempotency_key)`.
- [x] `PixProviderClient`: exceções tipadas `PixProviderException` e
      `PixProviderTimeoutException`; `PagarMePixProviderClient` mapeia
      `ResourceAccessException` → timeout e demais `RestClientException` →
      erro real.
- [x] `PixTransaction`: campo persistido `idempotencyKey` + transição `fail()`
      (PENDING → FAILED).
- [x] `TransactionRepository`/`PixTransactionJpaRepository`/adapter:
      `findByMerchantIdAndIdempotencyKey`.
- [x] `IdempotencyPersister` (bean, `TransactionTemplate` REQUIRES_NEW) —
      persiste a PENDING com a chave antes do PSP e **retorna o tx com a chave
      atribuída** (o fluxo principal reassina `tx = persistPending(tx, key)`).
- [x] `CreatePixChargeService.create`: valida chave (blank → 400), retry
      idempotente (replay com QR → mesma transação; PENDING sem QR → re-tenta
      PSP; FAILED → 409), criação com persistência antecipada, timeout →
      502 `psp_timeout`, erro real → FAILED + rethrow.
- [x] `CreatePixTransactionService`: mesmo fluxo (idempotencyKey).
- [x] DTOs das rotas de cobrança (`CreatePixTransactionRequest`,
      `PixChargeRequest`, portal charge/gift) + `@NotBlank idempotencyKey`;
      ports `Command` atualizados.
- [x] `GlobalExceptionHandler`: `IdempotencyConflictException` → 409
      `idempotency_conflict`; `PspTimeoutException` → 502 `psp_timeout`.
- [x] Mock PSP: perfil/flag para simular timeout em teste (ex.:
      `foxcash.psp.mock-timeout=true`).

## Verificação

- [x] `CreatePixChargeIdempotencyTest`: replay mesma chave → mesmo
      transactionId sem nova chamada ao PSP; retry pós-timeout conclui a
      cobrança; erro real → FAILED e retry → 409 `idempotency_conflict`;
      mesma chave em lojistas diferentes permitida; chave ausente → 400.
- [x] E2E: cenário de replay da mesma chave (mesmo `transactionId`) + timeout
      simulado; suite **10/10** sem regressão.
- [x] `mvn test` verde (204 + novos).
- [x] README: seção "Uso no backend" documenta `idempotencyKey` obrigatória.
- [x] Roadmap `created` → `verified`; requirements/design/task atualizados com
      contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
