# Spec 29 — Design: Estorno consistente

## Contexto

- `RegisterChargebackService` (Spec 19) debita o wallet sem mudar o status →
  webhook `REFUNDED` debita de novo (**C1**).
- `ProcessPixWebhookService` lança `IllegalStateException` em estado inesperado
  → 409 para o PSP (**M1**).
- Cheque de chargeback duplicado não é serializado → 500 na unique violation
  em corrida (**M3**).

## Decisões de arquitetura

### 1. `RegisterChargebackService` — fonte única de débito (resolve C1, M3, M4)

```java
@Transactional
public Result register(UUID merchantId, UUID transactionId, String reason) {
    // Lock pessimista na TRANSAÇÃO: serializa com o webhook (mesma ordem
    // tx -> merchant do ProcessPixWebhookService — evita deadlock, L1).
    PixTransaction transaction = transactionRepository
            .findByIdForUpdate(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));

    if (!transaction.merchantId().equals(merchantId)) {
        throw new IllegalArgumentException("Transacao nao pertence ao lojista informado");
    }
    if (chargebackRepository.findByTransactionId(transactionId).isPresent()) {
        throw new ChargebackAlreadyExistsException(transactionId);   // 409
    }
    if (transaction.status() != TransactionStatus.APPROVED) {
        throw new IllegalStateException("Chargeback so pode ser registrado em transacao APPROVED"); // 409 invalid_state
    }

    // Fonte única: o chargeback MARCA a transação REFUNDED + debita 1×.
    // O webhook REFUNDED posterior vira no-op (status já REFUNDED).
    transaction.refund();
    transactionRepository.save(transaction);

    Chargeback saved = chargebackRepository.save(Chargeback.register(...));

    Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    merchant.debit(transaction.amount());
    merchantRepository.save(merchant);
    ledgerRecorder.record(merchant, CHARGEBACK_DEBIT, transaction.amount(),
                          "CHARGEBACK", saved.id());      // Spec 27 (referência = chargeback)

    return new Result(saved.id());
}
```

- `TransactionRepository.findByIdForUpdate` (novo, mesmo padrão do
  `findByExternalReferenceForUpdate` da Spec 26): serializa o cheque de
  duplicidade E a transição de status contra o webhook concorrente.
- `ChargebackAlreadyExistsException` → **409 `chargeback_already_exists`**.
- Backstop: catch de `DataIntegrityViolationException` na unique
  `uq_chargebacks_transaction` → mesmo 409 (cobre corrida residual fora do
  lock).

### 2. `ProcessPixWebhookService` — tolerante a ordem (resolve M1)

A máquina de estados do domínio (`requireStatus`) continua como **backstop**;
o service decide antes de chamar se a transição é válida:

```java
public void process(WebhookEvent event) {
    PixTransaction tx = transactionRepository.findByExternalReferenceForUpdate(...)
            .orElseThrow(() -> new TransactionNotFoundException(...));
    TransactionStatus status = mapStatus(event.status());   // desconhecido -> 400

    switch (status) {
        case APPROVED -> applyIf(tx, PENDING,  () -> { tx.approve(); creditWallet(tx); });
        case REJECTED -> applyIf(tx, PENDING,  tx::reject);
        case REFUNDED -> applyIf(tx, APPROVED, () -> { tx.refund(); debitWallet(tx); });
        default -> throw new IllegalArgumentException("Status nao suportado: " + event.status());
    }
    transactionRepository.save(tx);   // save no-op seguro se nada mudou
}

/** Aplica a transição só se o estado atual for o esperado; senão no-op + alerta. */
private void applyIf(PixTransaction tx, TransactionStatus expected, Runnable effect) {
    if (tx.status() != expected) {
        log.warn("Webhook fora de ordem: status={} transacao={} (esperava {}; atual {}) — no-op",
                 expected, tx.id(), expected, tx.status());
        return;   // 204; nunca 409/500 para o PSP
    }
    effect.run();
}
```

- **Replay** (`PAID` com tx `APPROVED`, `REFUNDED` com tx `REFUNDED`): o
  `applyIf` vira no-op (estado ≠ esperado) — preserva a idempotência da
  Spec 18.
- **Fora de ordem** (`REFUNDED` de `PENDING`, `APPROVED` de `REFUNDED`,
  `PENDING` de `APPROVED`): no-op + log de alerta (evento fica rastreável;
  reconciliação da Spec 30 pode tratá-lo).
- **Efeito financeiro** (credit/debit) só ocorre dentro de transição válida e
  sob lock — nunca duplicado.
- `debitWallet`/`creditWallet` gravam o ledger (Spec 27) com a referência da
  transação.

### 3. Webhook status desconhecido

`mapStatus` continua lançando `IllegalArgumentException` → **400
`invalid_argument`** (contrato; nenhum efeito financeiro). É um erro do PSP
(payload inválido), não um estado de negócio — 400 é correto.

## Arquivos

- `domain/exception/ChargebackAlreadyExistsException.java` (novo)
- `domain/port/out/TransactionRepository.java`: `findByIdForUpdate(UUID id)`
- Alterados: `RegisterChargebackService` (lock + `refund()` + 409 + recorder),
  `ProcessPixWebhookService` (applyIf/no-op + recorder), `TransactionPersistenceAdapter`
  + `PixTransactionJpaRepository` (findByIdForUpdate), `GlobalExceptionHandler`
  (409 `chargeback_already_exists`)
- Testes: `RegisterChargebackServiceTest` (chargeback → REFUNDED + débito 1× +
  ledger; duplicado 409; não-APPROVED 409), `ProcessPixWebhookServiceTest`
  (fora de ordem no-op; REFUNDED pós-chargeback no-op; status desconhecido 400)
- E2E: cenário de chargeback → transação REFUNDED; webhook `REFUNDED`
  pós-chargeback não debita de novo

## Fora de escopo

- Ajuste de fatura por estorno pós-geração (decisão registrada na Spec 30).
- Recuperação automática de `REFUNDED` de `PENDING` (reconciliação — Spec 30).
- Estorno parcial (sempre do valor total, como hoje).
