---
name: Trace de transação — rastreio ponta a ponta (bot → gateway → PSP → ledger → webhook)
status: implemented
---

# Spec 125 — Design: Trace de transação

## D1 — Conceito

O suporte recebe "meu Pix de R$ 50 não caiu". Hoje, rastrear exige juntar
manualmente 5 tabelas (`pix_transactions`, `platform_activity_log`,
`wallet_ledger`, `master_ledger`, `notification_outbox`, `bot_leads`). O dado
está todo lá — amarrado por **`PixTransaction.id`** — mas não há endpoint nem
UI que monte a jornada.

Esta spec cria um **endpoint read-only de trace** que agrega as fontes
existentes por `transaction.id` e devolve uma **linha do tempo** com `stage`
(BOT → GATEWAY → PSP → LEDGER → WEBHOOK). O fio condutor é o UUID interno;
o `externalReference` (txid do PSP) e a `idempotencyKey` (do bot) são
**lookups alternativos** para o suporte chegar nele.

## D2 — Entrada e lookup

```
GET /api/v1/master/trace/transactions/{ref}
Authorization: Bearer <master session>
```

`ref` é resolvido nesta ordem:

1. `UUID.fromString(ref)` → `transactionRepository.findById` (UUID interno).
2. senão → `findByExternalReference(ref)` (txid/code do PSP — já usado pelo
   `ProcessPixWebhookService`, Spec 28: `code` = transactionId no Pagar.me).
3. senão → `findByIdempotencyKey` (chave do bot; é única por lojista, mas o
   lookup percorre e devolve 404 se houver ambiguidade — decisão: idempotency
   key sem `merchantId` pode colidir entre lojistas → devolver `409`? Não —
   simplicidade: o campo é único por lojista, não global; aceitar o primeiro e
   documentar; alternativa futura: exigir `?merchantId=`).

Inexistente/ambiguo → `404 TransactionNotFound` (sem revelar existência).

> Decisão D2.1: a rota é `/master/trace/transactions/{ref}` (ROLE_MASTER),
> espelhando o padrão `/api/v1/master/**` existente. Um lookup com
> `?merchantId=` é aditivo futuro (autoatendimento do lojista, fase 4+).

## D3 — Fontes e chaves

| Fonte | Chave de busca | Contribui para a timeline |
|---|---|---|
| `pix_transactions` | `id` | Cabeçalho: amount, status, timestamps, provider, `externalReference`, `endToEndId`, `profileId`, `botId` |
| `platform_activity_log` | `entityType=TRANSACTION` + `entityId=id` ⚠️ C4 | `CHARGE_CREATED`, `SALE_PAID`, `SALE_REFUNDED`, `DELIVERY_*` (details já mascarados) |
| `wallet_ledger` | `referenceType=TRANSACTION` + `referenceId=id` | `SALE_CREDIT` (+N, `balanceAfter`), `REFUND_DEBIT` (−N) |
| `master_ledger` | `referenceType` + `referenceId=id` | Receita da plataforma (quando aplicável) |
| `notification_outbox` | `entity_type=TRANSACTION` + `entity_id=id` ⚠️ C5 (fase 2) | `SALE_PAID_DELIVERED`: `attempts`, `lastError`, `nextAttemptAt` |
| `bot_leads`/`bot_funnel_events` | `transactionId=id` | `CHECKOUT_STARTED`, `PAID` (prova de que o bot "viu" a venda, Spec 75) |

A junção é por **id único** (lookup pontual) — sem impacto de query relevante,
desde que os índices do D6 existam.

## D4 — Contrato da resposta

```json
{
  "transactionId": "…",
  "merchantId": "…",
  "amount": "150.00",
  "status": "APPROVED",
  "externalReference": "code-do-psp",
  "endToEndId": "E000…",
  "profileId": "…",
  "createdAt": "…",
  "paidAt": "…",
  "bot": { "botId": "…", "platformUserId": "…" },
  "timeline": [
    { "at": "…", "stage": "BOT",     "event": "CHECKOUT_STARTED",  "detail": {} },
    { "at": "…", "stage": "GATEWAY", "event": "CHARGE_CREATED",    "detail": { "amount": "150.00" } },
    { "at": "…", "stage": "PSP",     "event": "WEBHOOK_RECEIVED", "detail": { "status": "PAID", "amount": "150.00" } },
    { "at": "…", "stage": "LEDGER",  "event": "SALE_CREDIT",      "detail": { "amount": "150.00", "balanceAfter": "820.00" } },
    { "at": "…", "stage": "WEBHOOK", "event": "SALE_PAID_DELIVERED", "detail": { "attempts": 1, "ok": true } },
    { "at": "…", "stage": "BOT",     "event": "PAID",             "detail": { "funnel": true } }
  ]
}
```

A **timeline** é a fusão ordenada por `at` das fontes. Itens ausentes porque a
etapa não ocorreu simplesmente não aparecem (C2). O `detail` de cada item vem
da fonte original (`activity.details` já mascarado pelo recorder; ledger com
`amount`/`balanceAfter`).

> Decisão D4.1: `status` é o estado atual da transação; a timeline mostra a
> evolução. Um trace de transação **cancelada/estornada** deve mostrar
> `SALE_REFUNDED`/`WEBHOOK` de estorno quando existirem.

## D5 — Merge da timeline

O serviço de trace (`TransactionTraceService`) consulta as fontes em paralelo
(ou sequencial, simples) e produz:

```java
record TraceItem(Instant at, TraceStage stage, String event, Map<String,Object> detail) {}

record TransactionTrace(
    PixTransaction transaction,          // cabeçalho
    MerchantBotRef bot,                  // botId + platformUserId (derivado da tx)
    List<TraceItem> timeline,            // ordenado por at
    List<WalletLedgerEntry> walletLedger,
    List<OutboxDelivery> deliveries)     // fase 2
```

- Activity → item `GATEWAY/PSP` conforme a ação (`CHARGE_CREATED` → GATEWAY;
  `SALE_PAID` → PSP; `SALE_REFUNDED` → PSP).
- Ledger → item `LEDGER` (`SALE_CREDIT`/`REFUND_DEBIT`).
- Funil → item `BOT` (`CHECKOUT_STARTED`/`PAID`).
- Outbox → item `WEBHOOK` (fase 2) com `status` real do evento
  (`PENDING`/`SENT`/`FAILED` — coluna `notification_outbox.status` já existe)
  + `attempts`/`lastError`/`nextAttemptAt`.
- Ordenação por `at`; empates por ordem canônica (BOT < GATEWAY < PSP < LEDGER
  < WEBHOOK).

## D6 — Mudanças estruturais (as partes caras)

### D6.1 — Activity filtrável por entidade (C4)

`PlatformActivityQuery` ganha `entityType`/`entityId` (opcionais) e o
`PlatformActivityRepository.query` filtra por eles. Migration
`V73__activity_entity_index.sql`:

```sql
CREATE INDEX ix_platform_activity_entity ON platform_activity_log (entity_type, entity_id, created_at);
```

(Sem isso, o trace de uma transação seria full-scan da activity — inaceitável
com volume.)

### D6.2 — Outbox referenciado (C5, fase 2)

`notification_outbox` ganha `entity_type`/`entity_id` (nullable; backfill
parseando o `payload` JSON existente — `data.transactionId` para
CHARGE_CREATED/SALE_PAID/SALE_REFUNDED). Migration:

```sql
ALTER TABLE notification_outbox ADD COLUMN entity_type VARCHAR(64);
ALTER TABLE notification_outbox ADD COLUMN entity_id UUID;
CREATE INDEX ix_outbox_entity ON notification_outbox (entity_type, entity_id, created_at);
```

`WebhookEventPublisher.publish` passa a receber a referência da origem
(`publish(merchantId, type, data, entityType, entityId)`) — overload mantém a
assinatura atual para chamadores sem referência. `NotificationOutboxPort` +
adapter ganham `findByEntity` e `savePending` com os novos campos.

## D7 — Segurança

- **RBAC**: o trace é **read-only** → segue o padrão dos endpoints master de
  **leitura** (`MasterPanelController`): rota sob `/api/v1/master/**` protegida
  por **`hasRole("MASTER")`** no `SecurityConfig` (Spec 13) — **sem**
  `MasterPermission` nova (as permissões finas da Spec 69 são para ações de
  escrita; leitura não exige). Sem ROLE_MASTER → 401/403.
- **Privacidade**: o `details` da activity já é mascarado na origem
  (`PlatformActivityRecorder.SENSITIVE_KEYS`); o trace **não adiciona** campos
  crus (ex.: `buyerIdHash` é hash — ok; `pixKey`/destino NUNCA em claro).
- **Cross-lojista**: o endpoint é por `transaction.id` global (master tem
  acesso a todos). Se a fase 4 abrir ao lojista, o filtro `merchantId` é
  obrigatório no service (decisão futura).

## D8 — Fases e ordem de implementação

| Fase | Conteúdo | Depende de |
|---|---|---|
| 1 | D6.1 (índice/filtro activity) + `TransactionTraceService` + `MasterTraceController` + DTO + testes | — |
| 2 | D6.2 (outbox referenciado + backfill) + item `WEBHOOK_DELIVERED` | Fase 1 |
| 3 | `trace_id` na `pix_transactions` + propagação no MDC (worker do outbox hoje roda sem requestId) + payload do webhook | Fase 1 (opcional) |
| 4 | Tela no console + (opcional) acesso do lojista ao próprio trace | Fases 1–2 |

A **fase 1 não exige traceId novo** — o `transaction.id` já é o fio condutor
(respondido no mapeamento: o traceId propagado só adiciona correlação de logs,
não é pré-requisito do endpoint).

## D9 — Arquivos previstos (fase 1)

- `infrastructure/inbound/rest/MasterTraceController.java` (novo)
- `infrastructure/inbound/rest/dto/TransactionTraceResponse.java` (novo)
- `application/service/TransactionTraceService.java` (novo)
- `domain/port/out/` — `PlatformActivityRepository` (filtro entityId),
  `WalletLedgerRepository`/`MasterLedgerRepository` (query por reference),
  `BotFunnelEventRepository` (por transactionId — já existe)
- `domain/model/activity/PlatformActivityQuery.java` (+entityType/entityId)
- `resources/db/migration/V73__trace_indexes.sql` (índice activity; fase 2: outbox)
- Testes: `TransactionTraceServiceTest`, `MasterTraceControllerTest`
