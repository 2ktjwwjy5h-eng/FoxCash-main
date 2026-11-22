# Spec 34 — Design: Ledger do master (receita da plataforma)

## Contexto

O ledger do lojista (Spec 27) não captura o lado da **plataforma**: quando uma
fatura é paga (33), o wallet do lojista debita, mas nada registra a receita do
dono. Esta spec adiciona `master_ledger` — receita/perda da plataforma gravada
**na mesma transação** do evento, com `balance_after` por lojista.

## Decisões de arquitetura

### Tabela `master_ledger` (migration V23)

```sql
CREATE TABLE master_ledger (
    id             UUID PRIMARY KEY,
    merchant_id    UUID NOT NULL REFERENCES merchants(id),  -- origem do dinheiro
    entry_type     VARCHAR(20) NOT NULL,  -- TRANSACTION_FEE | MONTHLY_FEE |
                                          -- RECEIVABLE_RECOVERY | ADMIN_RECOVERY |
                                          -- CHARGEBACK_LOSS
    amount         NUMERIC(15,2) NOT NULL,
    balance_after  NUMERIC(15,2) NOT NULL,   -- saldo da plataforma p/ o lojista
    reference_type VARCHAR(20),              -- INVOICE | RECEIVABLE | WITHDRAWAL
    reference_id   UUID,
    created_at     TIMESTAMP NOT NULL
);
CREATE INDEX ix_master_ledger_merchant_created ON master_ledger(merchant_id, created_at);
CREATE INDEX ix_master_ledger_ref ON master_ledger(reference_type, reference_id);
-- Idempotência por origem: cada evento gera UMA entry por TIPO. O entry_type
-- faz parte da chave — uma fatura paga gera TRANSACTION_FEE + MONTHLY_FEE com a
-- mesma referência (invoice.id), então a chave precisa do tipo.
CREATE UNIQUE INDEX uq_master_ledger_ref
    ON master_ledger(merchant_id, entry_type, reference_type, reference_id);
```

Mesma filosofia da Spec 27: **imutável**, unique por origem (idempotência —
cada evento gera exatamente uma entrada), `balance_after` sob serialização.

### Gravação: `MasterLedgerRecorder` (mesma transação)

```java
// application/service/MasterLedgerRecorder.java
@Service
public class MasterLedgerRecorder {
    // Chamado DENTRO do @Transactional do fluxo que origina a receita/perda.
    public void record(UUID merchantId, MasterLedgerEntryType type, Money amount,
                       String referenceType, UUID referenceId);
}
```

Pontos de gravação:

| Evento (spec) | Entry | Referência |
|---|---|---|
| Fatura paga (33) | `TRANSACTION_FEE` (0,50 × count) + `MONTHLY_FEE` (69,95) | invoice.id |
| Receivable de estorno pago (33) | `RECEIVABLE_RECOVERY` (valor recebido) | receivable.id |
| Saque administrativo (32) | `ADMIN_RECOVERY` (valor recolhido) | withdrawal.id |
| Write-off de receivable (34) | `CHARGEBACK_LOSS` (valor baixado) | receivable.id |

> **Sem dupla contagem:** `RECEIVABLE_RECOVERY` é registrado **apenas** para
> receivables de estorno (`CHARGEBACK`/`REFUND`) quitados pelo sweep da Spec 33
> (`collectReceivables`). Receivable de origem `INVOICE` é quitado junto com a
> fatura e **não** gera `RECEIVABLE_RECOVERY` — a receita dessa fatura já está
> nas entries `TRANSACTION_FEE` + `MONTHLY_FEE`.

- `balance_after` calculado por lojista (soma das entries anteriores + atual).
  Serialização: os fluxos origem já adquirem lock no merchant
  (`findByIdForUpdate`, Spec 26) antes de chamar o recorder — mesma garantia
  do ledger do lojista (Spec 27).
- Unique por `(merchant_id, entry_type, reference_type, reference_id)` evita
  duplicidade em replay/corrida (fatura paga 1× → 1 par de entries, uma por
  tipo).

### Domínio

```java
// domain/model/revenue/MasterLedgerEntryType.java
// TRANSACTION_FEE, MONTHLY_FEE, RECEIVABLE_RECOVERY, ADMIN_RECOVERY, CHARGEBACK_LOSS
// domain/model/revenue/MasterLedgerEntry.java (record imutável)
// domain/port/out/MasterLedgerRepository.java
//   save, findByMerchantId (paginado, filtros type/from/to),
//   sumByMerchantIdAndTypeBetween, findLatestByMerchantId  (para balance_after)
```

### Write-off (baixa de receivable) — ação master

```java
@Transactional
public void writeOff(UUID masterId, UUID receivableId, String reason) {
    Receivable receivable = receivableRepository.findById(receivableId).orElseThrow(404);
    if (receivable.status() == PAID) throw 409 invalid_state;      // já baixado/pago
    if (reason == null || reason.isBlank()) throw 400;
    Money loss = receivable.outstandingAmount();
    receivable.markPaid(now);                                       // baixa (PAID por baixa)
    receivableRepository.save(receivable);
    masterLedgerRecorder.record(merchantId, CHARGEBACK_LOSS, loss,
                                "RECEIVABLE", receivableId);
    masterAuditRecorder.record(masterId, "RECEIVABLE_WRITE_OFF", "RECEIVABLE",
                               receivableId, before, after, reason, ip);  // Spec 37
}
```

### REST (ROLE_MASTER)

| Rota | Resposta |
|---|---|
| `GET /api/v1/master/revenue/summary?from=&to=` | `{transactionFees, monthlyFees, recoveries, losses, total, net}` |
| `GET /api/v1/master/revenue/ledger?merchantId=&type=&from=&to=&page=&size=` | `PageResponse<MasterLedgerEntryResponse>` |
| `POST /api/v1/master/receivables/{id}/write-off` `{reason}` | 200 `{loss}` |

- `summary` = Σ entries do período por tipo (SQL `SUM`/`GROUP BY` + COALESCE);
  `net = (transactionFees + monthlyFees + recoveries + adminRecoveries) − losses`.
- `QueryParamParser` para 400; principal do master = `authentication.getPrincipal()`.

## Arquivos

- Migration `V23__master_ledger.sql`
- `domain/model/revenue/MasterLedgerEntry*.java` + port out + adapter JPA
- `application/service/MasterLedgerRecorder.java` (novo)
- `application/service/MasterRevenueService.java` + port in (summary/ledger)
- `application/service/ReceivableWriteOffService.java` (novo; audita via Spec 37)
- `infrastructure/inbound/rest/MasterRevenueController.java` + DTOs
- Alterados: `InvoiceCollectionService` (33 → entries), `ReceivableService`
  (pagamento → `RECEIVABLE_RECOVERY`), `MasterWithdrawalService` (32 →
  `ADMIN_RECOVERY`)
- Testes: `MasterLedgerRecorderTest`, `MasterRevenueServiceTest`,
  `ReceivableWriteOffServiceTest`
- E2E: cenário 14 (fatura paga → receita; write-off → perda; summary)

## Fora de escopo

- Saldo consolidado da plataforma em múltiplas contas — spec futura.
- Relatórios fiscais (DRE/fiscal) — o ledger é a base de dados, não o relatório.
- Reserva de valor — spec futura.
