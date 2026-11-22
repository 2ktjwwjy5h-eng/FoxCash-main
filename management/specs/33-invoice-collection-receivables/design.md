# Spec 33 — Design: Faturamento automático e contas a receber

## Contexto

A fatura (`Invoice`, Spec 6) nasce `PENDING` e fica para sempre nesse estado —
ninguém a cobra. O wallet só muda por venda (crédito) e saque/estorno (débito).
Esta spec adiciona o **ciclo de cobrança**: débito automático no vencimento,
pagamento parcial e **dívida formal** (`receivables`) com bloqueio de saque por
inadimplência.

## Decisões de arquitetura

### Tabelas (migrations V22)

```sql
-- Receivables: dívida formal do lojista com a plataforma.
CREATE TABLE receivables (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    origin       VARCHAR(20) NOT NULL,   -- INVOICE | CHARGEBACK | REFUND | ADJUSTMENT
    reference_id UUID NOT NULL,          -- invoice.id / chargeback.id / transaction.id
    amount       NUMERIC(15,2) NOT NULL, -- valor devedor total
    paid_amount  NUMERIC(15,2) NOT NULL DEFAULT 0.00,
    status       VARCHAR(20) NOT NULL,   -- OPEN | PARTIAL | PAID
    created_at   TIMESTAMP NOT NULL,
    paid_at      TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL
);
CREATE INDEX ix_receivables_merchant ON receivables(merchant_id, status);
-- Idempotência: uma dívida por origem.
CREATE UNIQUE INDEX uq_receivables_origin ON receivables(merchant_id, origin, reference_id);

-- invoices: rastreabilidade da cobrança.
ALTER TABLE invoices ADD COLUMN paid_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00;
ALTER TABLE invoices ADD COLUMN collected_at TIMESTAMP;
```

- `Invoice.markPaid()` já existe; novas transições: cobrança parcial mantém
  `PENDING`/`OVERDUE` com `paid_amount` (o pagamento parcial **não** muda o
  status para `PAID` até quitar).

### Domínio

```java
// domain/model/billing/ReceivableOrigin.java  -> INVOICE, CHARGEBACK, REFUND, ADJUSTMENT
// domain/model/billing/ReceivableStatus.java  -> OPEN, PARTIAL, PAID
// domain/model/billing/Receivable.java (record com create/applyPayment/markPaid)

// domain/model/merchant/Merchant.java (novo motivo de bloqueio de saque)
public void ensureNoOutstandingDebt(boolean blockOnDebt) {
    if (blockOnDebt && outstandingDebt) {
        throw new OutstandingDebtException(id);   // 409 outstanding_debt
    }
}
```

### `InvoiceCollectionService` (novo) — cobrança no vencimento

```java
@Transactional
public CollectionResult collectDueInvoices(Instant now) {
    // Faturas não quitadas e vencidas: PENDING OU OVERDUE com saldo devedor.
    // A re-execução do job re-cobra OVERDUE (fecha o ciclo da dívida formal).
    List<Invoice> due = invoiceRepository.findUnpaidDueOnOrBefore(now);
    int collected = 0;
    for (Invoice invoice : due) {
        collect(invoice.merchantId(), invoice.id(), now);
        collected++;
    }
    // 2) Quita receivables de estorno (CHARGEBACK/REFUND) com o saldo disponível.
    collectReceivables(now);
    return new CollectionResult(collected);
}

@Transactional
public CollectionResult collect(UUID merchantId, UUID invoiceId, Instant now) {
    Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow(404);
    Money outstanding = invoice.totalAmount().subtract(Money.of(invoice.paidAmount()));
    if (outstanding.signum() <= 0) return noop;          // quitada -> idempotente
    Merchant merchant = merchantRepository.findByIdForUpdate(merchantId).orElseThrow(404);

    Money available = merchant.walletBalance();
    Money debit = min(outstanding, available);           // pagamento parcial
    if (debit.signum() > 0) {
        merchant.debit(debit);                             // débito no wallet
        invoice.applyPayment(debit, now);                  // paid_amount += debit
        ledgerRecorder.record(merchant, INVOICE_FEE_DEBIT, debit, "INVOICE", invoice.id()); // Spec 27
    }
    if (invoice.isFullyPaid()) {
        invoice.markPaid();
        // Receivable INVOICE quitado junto (a receita da plataforma fica para a
        // Spec 34: TRANSACTION_FEE + MONTHLY_FEE — sem RECEIVABLE_RECOVERY aqui).
        receivableRepository.markPaidIfOpen(merchantId, INVOICE, invoice.id(), now);
    } else {
        invoice.markOverdue();
        receivableRepository.upsertOpen(merchantId, INVOICE, invoice.id(),
                                        outstanding.subtract(debit), now);  // dívida restante
    }
    merchantRepository.save(merchant);
    invoiceRepository.save(invoice);
    return new CollectionResult(debit, outstanding.subtract(debit));
}
```

- **Idempotência:** o gate é `outstanding > 0` (fatura quitada → no-op);
  re-execução vê `PAID`/quitada e retorna no-op. A unique `uq_receivables_origin`
  protege contra receivable duplicado em corrida.
- **Fatura `OVERDUE` re-cobrada:** o `collect` não exige mais `PENDING` — uma
  fatura `OVERDUE` com saldo devedor é re-processada quando o lojista tem saldo
  (fecha o ciclo: o receivable `INVOICE` deixa de ficar eternamente `OPEN`).
- **`collectReceivables` (fecha o ciclo da dívida de estorno):** receivables
  `OPEN`/`PARTIAL` de origem `CHARGEBACK`/`REFUND` são quitados com o saldo
  disponível do lojista, na mesma transação do job: debita o wallet,
  `Receivable.applyPayment(...)` e a Spec 34 registra `RECEIVABLE_RECOVERY` no
  valor efetivamente recebido (a receita da fatura paga já é coberta por
  `TRANSACTION_FEE` + `MONTHLY_FEE` — sem dupla contagem).
- **Estorno não coberto (Spec 29):** no chargeback/refund, se o saldo do
  lojista ficar **negativo**, o mesmo fluxo cria receivable `CHARGEBACK`/
  `REFUND` no valor do negativo (dívida) — chamada a partir do
  `RegisterChargebackService`/webhook (Spec 29) via `ReceivableRecorder`.

### Bloqueio de saque por dívida (Spec 32 integra)

`WithdrawalService.withdraw` (vendedor) passa a validar, sob o lock:
`merchant.ensureNoOutstandingDebt(billingProperties.isBlockWithdrawalsOnDebt())`
→ 409 `outstanding_debt`. O **saque administrativo** (master) **não** valida
isso (o master recolhe justamente o devedor).

### Scheduler

`InvoiceCollectionScheduler` (ShedLock `foxcash-invoice-collection`, cron
`foxcash.scheduler.invoice-collection-cron`, default diário 02:30 UTC) — mesmo
padrão Spec 6/16.

### REST (master)

| Rota | Resposta |
|---|---|
| `POST /api/v1/master/merchants/{id}/invoices/{invoiceId}/collect` | 200 `{debit, remaining}` |
| `GET /api/v1/master/receivables?merchantId=&status=&origin=&from=&to=&page=&size=` | `PageResponse<{id, merchantId, origin, amount, paidAmount, status, createdAt, paidAt}>` |
| `GET /api/v1/master/merchants/{id}/receivables` | idem (só do lojista) |

## Arquivos

- Migrations `V22__receivables_invoice_collection.sql`
- `domain/model/billing/Receivable*.java`; `domain/exception/OutstandingDebtException.java`
- `domain/port/out/ReceivableRepository.java` + adapter JPA
- `InvoiceRepository`: `findUnpaidDueOnOrBefore` (PENDING ou OVERDUE com saldo
  devedor), `save` (paid_amount)
- `application/service/InvoiceCollectionService.java` + port in
- `application/service/ReceivableRecorder.java` (chamado pela Spec 29)
- `infrastructure/scheduler/InvoiceCollectionScheduler.java`
- `infrastructure/inbound/rest/ReceivableController.java` + DTOs
- Alterados: `Invoice` (applyPayment), `Merchant` (outstandingDebt),
  `WithdrawalService` (409 `outstanding_debt`), `RegisterChargebackService`/
  webhook (receivable por saldo negativo)
- Testes: `InvoiceCollectionServiceTest` (total/parcial/idempotência),
  `ReceivableServiceTest`, `WithdrawalServiceTest` (bloqueio por dívida)
- E2E: cenário 13 (cobrança automática, parcial, bloqueio por dívida)

## Fora de escopo

- Receita da plataforma (ledger do master) — Spec 34.
- Baixa (write-off) de receivable — Spec 34 + auditoria (Spec 37).
- Reserva de valor / retenção preventiva — spec futura.
- Notificação ao lojista — Spec 35 (publica eventos no outbox).
