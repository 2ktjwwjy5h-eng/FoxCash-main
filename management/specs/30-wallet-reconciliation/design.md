# Spec 30 — Design: Reconciliação e integridade do saldo

## Contexto

O ledger (Spec 27) torna o saldo auditável; esta spec o torna **verificável**:
um job valida `wallet_balance` contra o ledger e registra divergências. Também
endurece o contrato: fatura via COUNT (L3), escala normalizada no JSON (L2) e
decisão explícita sobre estorno pós-fatura (M2).

## Decisões de arquitetura

### Tabela `reconciliation_issues` (migration V30)

```sql
CREATE TABLE reconciliation_issues (
    id             UUID PRIMARY KEY,
    merchant_id    UUID NOT NULL REFERENCES merchants(id),
    expected_balance NUMERIC(15,2) NOT NULL,   -- balance_after da última entry
    actual_balance   NUMERIC(15,2) NOT NULL,   -- merchants.wallet_balance
    status         VARCHAR(20) NOT NULL,       -- OPEN | RESOLVED
    detected_at    TIMESTAMP NOT NULL,
    resolved_at    TIMESTAMP
);
CREATE INDEX ix_reconciliation_issues_merchant ON reconciliation_issues(merchant_id, status);
-- Uma issue aberta por lojista divergente (idempotência do job):
CREATE UNIQUE INDEX uq_reconciliation_open ON reconciliation_issues(merchant_id)
    WHERE status = 'OPEN';
```

### Job de reconciliação

```java
// application/service/WalletReconciliationService.java
@Service
public class WalletReconciliationService {
    public ReconciliationReport run() {            // valida todos os lojistas
        int checked = 0, ok = 0;
        for (Merchant m : merchantRepository.findAll()) {
            checked++;
            Optional<WalletLedgerEntry> latest = ledgerRepository.findLatestByMerchantId(m.id());
            BigDecimal expected = latest.map(e -> e.balanceAfter().amount())
                                        .orElse(m.walletBalance().amount()); // sem ledger = abrevia
            if (expected.compareTo(m.walletBalance().amount()) == 0) { ok++; continue; }
            issuesRepository.open(m.id(), expected, m.walletBalance().amount()); // idempotente
        }
        return new ReconciliationReport(checked, ok, checked - ok);
    }
}
```

- **`open()` idempotente:** `INSERT ... ON CONFLICT` na unique parcial
  `uq_reconciliation_open` — re-execução não duplica issue do mesmo lojista
  (mantém a `OPEN` com `detected_at` original).
- **Scheduler** `WalletReconciliationScheduler` (ShedLock
  `foxcash-wallet-reconciliation`, cron `foxcash.scheduler.reconciliation-cron`,
  default diário 04:00 UTC) — mesmo padrão dos schedulers das Specs 6/16.
- **Órfãs de cobrança:** no mesmo run, contabiliza transações `PENDING` sem
  `externalReference` (timeout da Spec 28) e expõe no resumo como
  `orphanCharges` — visibilidade, sem ação automática.

### REST (ROLE_MASTER)

| Rota | Resposta |
|---|---|
| `POST /api/v1/master/reconciliation/run` | `{checked, ok, issues, orphanCharges}` (200) |
| `GET /api/v1/master/reconciliation/issues?merchantId=&status=&page=&size=` | `PageResponse<{issueId, merchantId, expectedBalance, actualBalance, status, detectedAt, resolvedAt}>` |

- `QueryParamParser` para 400 `invalid_argument` (padrão Spec 20/25).
- `issues` reusa o padrão `PageResponse` (Spec 8).

### Fatura via COUNT (L3)

`GenerateInvoiceService.generateForPeriod` troca:

```java
// antes: int approvedCount = transactionRepository
//             .findApprovedByMerchantIdBetween(merchantId, from, to).size();
// depois:
long approvedCount = transactionRepository
        .countByMerchantIdAndStatusAndCreatedAtBetween(merchantId, APPROVED, from, to);
```

`countByMerchantIdAndStatusAndCreatedAtBetween` já existe (Spec 25). A fatura
permanece **snapshot** (decisão M2): contagem do período no momento da geração;
estorno posterior não altera a fatura já emitida. `InvoiceCalculator.draft`
recebe `int` — ajustar para `long` ou converter.

### Escala normalizada (L2)

As respostas financeiras usam `Money.amount()`/`BigDecimal` direto do
PostgreSQL (`SUM` pode devolver `150.0`). Normalizar com
`setScale(2, RoundingMode.HALF_EVEN)` no mapeamento dos DTOs:

- `PortalSalesMetricsResponse`/`PortalSaleResponse`/`PortalSaleDetailResponse`
  (Spec 25), `PortalWallet*` (Spec 22), `PortalDashboard*` (Spec 21),
  `MasterDashboard*` (Spec 15), `WalletLedgerEntryResponse` (Spec 27).
- Helper `Money`/util (ex.: `Money.normalize(BigDecimal)`) para não repetir a
  regra.

## Arquivos

- `src/main/resources/db/migration/V30__reconciliation_issues.sql` (novo)
- `domain/model/reconciliation/ReconciliationIssue.java` (novo)
- `domain/port/out/ReconciliationIssueRepository.java` (novo) + adapter JPA
- `application/service/WalletReconciliationService.java` (novo) + port in
- `infrastructure/scheduler/WalletReconciliationScheduler.java` (novo)
- `infrastructure/inbound/rest/ReconciliationController.java` (novo) + DTOs
- Alterados: `GenerateInvoiceService` (COUNT), DTOs financeiros (escala 2),
  `InvoiceCalculator` (int → long)
- Testes: `WalletReconciliationServiceTest`, `ReconciliationControllerTest`,
  `GenerateInvoiceServiceTest` (COUNT), teste de escala
- E2E: cenário de `run` manual + issue simulada

## Fora de escopo

- Correção automática de divergência (issue → decisão do master; correção
  manual via UPDATE documentada no runbook).
- Fatura de ajuste por estorno pós-geração (spec futura, referenciada).
- Alertas externos (e-mail/webhook) — ficam no padrão outbox de notificação
  (Spec 7) se necessário em spec futura.
