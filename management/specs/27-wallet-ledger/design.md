# Spec 27 — Design: Wallet ledger (movimentações financeiras auditáveis)

## Contexto

O saldo do lojista vive em `merchants.wallet_balance` e é mutado em 4 pontos,
todos já sob lock pessimista (Spec 26):

| Fluxo | Classe | Mutação | Lock |
|---|---|---|---|
| Webhook `PAID` | `ProcessPixWebhookService.creditWallet` | `Merchant.credit` | `findByIdForUpdate` |
| Webhook `REFUNDED` | `ProcessPixWebhookService.debitWallet` | `Merchant.debit` | `findByIdForUpdate` |
| Chargeback | `RegisterChargebackService` | `Merchant.debit` | `findByIdForUpdate` |
| Saque | `WithdrawalService` | `Merchant.withdraw` | `findByIdForUpdate` |

Não há histórico: um bug de crédito/débito é invisível (diagnóstico **C3**).
Esta spec adiciona o **ledger** — movimentações imutáveis gravadas na mesma
transação do efeito, com `balance_after`, permitindo auditoria e reconciliação
(Spec 30).

## Decisões de arquitetura

### Tabela `wallet_ledger` (migration V17) — imutável

```sql
CREATE TABLE wallet_ledger (
    id             UUID PRIMARY KEY,
    merchant_id    UUID NOT NULL REFERENCES merchants(id),
    entry_type     VARCHAR(20) NOT NULL,   -- OPENING_BALANCE | SALE_CREDIT |
                                           -- REFUND_DEBIT | CHARGEBACK_DEBIT |
                                           -- WITHDRAWAL_DEBIT
    amount         NUMERIC(15,2) NOT NULL,
    balance_after  NUMERIC(15,2) NOT NULL,
    reference_type VARCHAR(20),            -- TRANSACTION | WITHDRAWAL |
                                           -- CHARGEBACK | NULL (opening)
    reference_id   UUID,
    created_at     TIMESTAMP NOT NULL
);
CREATE INDEX ix_wallet_ledger_merchant_created ON wallet_ledger(merchant_id, created_at);
CREATE INDEX ix_wallet_ledger_ref ON wallet_ledger(reference_type, reference_id);
-- Idempotência por origem: cada fonte financeira gera UMA entry por TIPO.
-- O entry_type faz parte da chave: uma transação aprovada gera SALE_CREDIT e,
-- se estornada depois, REFUND_DEBIT — a mesma origem gera entries diferentes.
CREATE UNIQUE INDEX uq_wallet_ledger_ref
    ON wallet_ledger(merchant_id, entry_type, reference_type, reference_id);
```

- **`balance_after`** = saldo do merchant **após** a mutação (imutável; nunca
  editado). Calculado sob o lock pessimista já existente — não há corrida.
- **Imutabilidade** = nenhuma atualização/delete por aplicação; apenas INSERT.
  Sem `@Version`/soft-delete: o histórico não muda.

### Backfill `OPENING_BALANCE` (runner idempotente no boot)

`OpeningBalanceSeedRunner` (`ApplicationRunner`): para cada lojista sem nenhuma
entry no ledger, grava `OPENING_BALANCE(amount = wallet_balance,
balance_after = wallet_balance, reference = NULL)`. Re-boot não duplica
(constraint única aceita `NULL` em `reference_id` — sem colisão entre
openings de lojistas diferentes porque a uniqueness inclui `merchant_id`).

> Nota de implementação: como `reference_id` é `NULL` para openings, a
> constraint única `(merchant_id, entry_type, reference_type, reference_id)` não
> colide entre openings do mesmo lojista porque no PostgreSQL `UNIQUE` trata
> `NULL` como distinto. O runner garante "um opening por lojista" com o
> `existsByMerchantId` antes do INSERT (idempotência na aplicação).

### Domínio e portas

```java
// domain/model/wallet/WalletLedgerEntryType.java
public enum WalletLedgerEntryType { OPENING_BALANCE, SALE_CREDIT, REFUND_DEBIT,
                                    CHARGEBACK_DEBIT, WITHDRAWAL_DEBIT }

// domain/model/wallet/WalletLedgerEntry.java (record imutável)
public record WalletLedgerEntry(UUID id, UUID merchantId, WalletLedgerEntryType type,
                                Money amount, Money balanceAfter, String referenceType,
                                UUID referenceId, Instant createdAt) { }

// domain/port/out/WalletLedgerRepository.java
WalletLedgerEntry save(WalletLedgerEntry entry);
boolean existsByMerchantId(UUID merchantId);                  // opening
PageResult<WalletLedgerEntry> findByMerchantId(UUID merchantId,
                                               WalletLedgerEntryType type,
                                               Instant from, Instant to,
                                               int page, int size);
BigDecimal sumAmountByMerchantId(UUID merchantId);            // Spec 30
Optional<WalletLedgerEntry> findLatestByMerchantId(UUID merchantId); // Spec 30
```

### Gravação centralizada: `WalletLedgerRecorder`

Os 4 services já mutam o merchant (com validações distintas — `credit`,
`debit`, `withdraw`). Para não duplicar lógica nem deixar o ledger esquecível,
um componente de aplicação centraliza a gravação:

```java
// application/service/WalletLedgerRecorder.java
@Service
public class WalletLedgerRecorder {
    // Grava UMA entry após a mutação; balanceAfter = merchant.walletBalance()
    // (já mutado pelo chamador). Chamado DENTRO do @Transactional do chamador.
    public WalletLedgerEntry record(Merchant merchant, WalletLedgerEntryType type,
                                    Money amount, String referenceType, UUID referenceId);
}
```

- `ProcessPixWebhookService.creditWallet` → `record(merchant, SALE_CREDIT, tx.amount(), "TRANSACTION", tx.id())`
- `ProcessPixWebhookService.debitWallet` → `record(merchant, REFUND_DEBIT, tx.amount(), "TRANSACTION", tx.id())`
- `RegisterChargebackService` → `record(merchant, CHARGEBACK_DEBIT, tx.amount(), "CHARGEBACK", savedChargeback.id())`
- `WithdrawalService` → `record(merchant, WITHDRAWAL_DEBIT, wd.amount(), "WITHDRAWAL", withdrawal.id())`

**Idempotência:** o replay de webhook já é no-op antes de mutar (Spec 18/26),
então a entry nunca é gravada 2× pelo mesmo evento; a constraint única é o
**backstop** contra corrida (INSERT concorrente da mesma referência → violação
→ rollback da transação inteira, sem efeito parcial).

### Consulta (somente leitura)

- `QueryWalletLedgerUseCase` + `QueryWalletLedgerService` (paginado, filtros
  `type`/`from`/`to`, `QueryParamParser` para 400 `invalid_argument`).
- `WalletLedgerController` (dois mapeamentos):
  - `GET /api/v1/master/merchants/{merchantId}/ledger` — `ROLE_MASTER`
    (auditoria de qualquer lojista);
  - `GET /api/v1/portal/wallet/ledger` — `ROLE_MERCHANT_PORTAL` (só o próprio;
    principal = merchantId; sem parâmetro de lojista — impossível IDOR).
- DTO `WalletLedgerEntryResponse {entryId, type, amount, balanceAfter,
  referenceType, referenceId, createdAt}` + `PageResponse`.

## Arquivos

- `src/main/resources/db/migration/V17__wallet_ledger.sql` (novo)
- `domain/model/wallet/WalletLedgerEntry.java`, `WalletLedgerEntryType.java` (novos)
- `domain/port/out/WalletLedgerRepository.java` (novo)
- `domain/port/in/QueryWalletLedgerUseCase.java` (novo)
- `application/service/WalletLedgerRecorder.java` (novo)
- `application/service/QueryWalletLedgerService.java` (novo)
- `application/service/OpeningBalanceSeedRunner.java` (novo)
- `infrastructure/outbound/persistence/entity/WalletLedgerJpaEntity.java` (novo)
- `infrastructure/outbound/persistence/mapper/WalletLedgerMapper.java` (novo)
- `infrastructure/outbound/persistence/repository/WalletLedgerJpaRepository.java` (novo)
- `infrastructure/outbound/persistence/adapter/WalletLedgerPersistenceAdapter.java` (novo)
- `infrastructure/inbound/rest/WalletLedgerController.java` + DTO (novos)
- Alterados: `ProcessPixWebhookService`, `RegisterChargebackService`,
  `WithdrawalService` (gravam ledger após mutar o saldo)
- Testes: `WalletLedgerRecorderTest`, `QueryWalletLedgerServiceTest` (novos);
  ajustes nos testes de webhook/chargeback/saque para verificar a entry

## Fora de escopo

- Reconciliação automática (`wallet_balance = Σ ledger`) — Spec 30.
- Reconstrução do histórico passado (decidido: backfill apenas do saldo atual).
- Ledger de faturas/taxas (taxas são cobradas via fatura, não do wallet).
- Frontend (contrato REST é o entregável).
