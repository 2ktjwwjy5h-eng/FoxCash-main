---
name: Trace de transação — traceId propagado (fase 3) + UI no console master (fase 4)
status: audited
---

# Spec 127 — Design: Trace de transação (traceId + UI)

## D1 — Conceito

O **traceId de negócio de uma cobrança É o `pix_transactions.id`**: estável do
nascimento à morte (a idempotência da Spec 28 garante que o retry reusa a MESMA
transação), único, e já presente em todas as fontes da jornada (activity
`entityId`, ledger `referenceId`, outbox `data.transactionId`, funil). A fase 3
torna essa convenção **explícita**: coluna `trace_id` (backfill `= id`), campo no
response do trace, `data.traceId` no payload do outbox e MDC no worker.

Decisão: **nenhuma mudança de domínio financeiro** — observabilidade apenas.
O campo `trace_id` existe para o vínculo canônico (visível no banco, no trace,
nos logs), não para mudar comportamento.

## D2 — Migration V74 (`V74__pix_transactions_trace_id.sql`)

```sql
ALTER TABLE pix_transactions ADD COLUMN trace_id UUID;
UPDATE pix_transactions SET trace_id = id WHERE trace_id IS NULL;
ALTER TABLE pix_transactions ALTER COLUMN trace_id SET NOT NULL;
```

- Backfill: transações legadas ganham `trace_id = id` (o id é o trace de negócio).
- `NOT NULL` após o backfill — toda transação nova tem traceId (o `id` nasce no
  `PixTransaction.create`, `UUID.randomUUID()`).
- Sem índice: o `trace_id` não é chave de lookup (o endpoint resolve por
  id/externalReference/idempotencyKey — Spec 125); adicionar índice só se um dia
  houver consulta por traceId.
- V74 confirmado livre (V73 usado pela Spec 125 — índices do trace).

## D3 — Camada de domínio e infra (traceId)

| Arquivo | Mudança |
|---|---|
| `PixTransaction` (model) | Getter `traceId()` → `return id` + Javadoc (o traceId de negócio é o id; a coluna existe para o vínculo explícito). **Sem campo novo, sem mudar construtores** (KISS — os ~10 overloads de create/restore não são tocados). |
| `PixTransactionJpaEntity` | Campo `traceId` (`@Column(name = "trace_id", nullable = false)`) + getter/setter. **Sem mudar construtores** (o mapper seta pós-construtor, padrão do `setProvider`/`setProfileId`). |
| `PixTransactionMapper.toEntity` | `entity.setTraceId(transaction.traceId())` (mesmo bloco do provider/profileId). |
| `PixTransactionMapper.toDomain` | Sem mudança (o model deriva `traceId()` do id). |
| `TransactionTraceResponse` | Campo `traceId` no record + preenchido no `from(...)` (`tx.traceId()`). |

## D4 — Propagação no payload do outbox (`data.traceId`)

Dois pontos publicam eventos de transação com `Map` de dados — ambos ganham
`traceId` explícito:

1. `PixChargePersistence.attachAndPublish` — `CHARGE_CREATED`:
   `Map.of("transactionId", saved.id(), "amount", ..., "status", ..., "traceId", saved.id())`.
2. `ProcessPixWebhookService.publishSaleEvent` — `SALE_PAID`/`SALE_REFUNDED`:
   `data.put("traceId", transaction.id())` junto do `transactionId` existente.

O `OutboxNotification` já carrega `entityType`/`entityId` (Spec 125) — o
`data.traceId` é redundante com o `transactionId` HOJE, mas torna o contrato do
payload auto-documentado e prepara receptores que não conhecem a convenção.

## D5 — MDC `traceId` no worker do outbox (C4)

`NotificationProcessor.process` — o worker roda em thread própria (sem
`RequestIdFilter`):

```java
@Transactional
public boolean process(OutboxNotification notification) {
    // Spec 127: correlação de logs — o worker loga com o traceId da transação.
    String traceId = traceIdOf(notification);
    if (traceId != null) {
        MDC.put("traceId", traceId);
    }
    try {
        // ... corpo atual (delivery + counters Spec 126) ...
    } finally {
        if (traceId != null) {
            MDC.remove("traceId");
        }
    }
}

/** traceId de negócio do evento: entityId quando a origem é uma transação. */
private static String traceIdOf(OutboxNotification n) {
    return "TRANSACTION".equals(n.entityType()) && n.entityId() != null
            ? n.entityId().toString() : null;
}
```

- Fonte: a **referência do outbox** (`entityType == "TRANSACTION"` → `entityId`),
  não o parse do payload JSON (mais barato e já curado pela Spec 125).
- O log estruturado (prod) **já emite `traceId=%X{traceId:-}`** (logback-spring.xml)
  e o Axiom usa `includeMdc=true` — **nenhuma mudança de logback**.
- Eventos sem referência (OUTBOX_DEAD, JOB_FAILED) não setam o campo (sem
  poluição); o `finally` garante que o MDC da thread do pool não vaza entre
  processamentos.
- Nota: o `%X{traceId:-}` do logback também é usado pelo Micrometer Tracing
  (Spec 57) quando há span ativo — o valor de negócio aqui é independente
  (mesmo campo, contexto diferente; não conflita — requestId/traceId de tracing
  são vazios quando não há trace ativo).

## D6 — UI no Master Console (fase 4)

Console: React 18 + TypeScript + Vite (`console/`), CSS puro
(`console/src/styles.css`), fetch wrapper (`apiClient.ts`), sem lib de testes.

| Arquivo | Mudança |
|---|---|
| `console/src/types.ts` | `TransactionTraceResponse` (`traceId`, `transactionId`, `merchantId`, `amount`, `status`, `externalReference`, `endToEndId`, `provider`, `profileId`, `idempotencyKey`, `createdAt`, `paidAt`, `bot: BotRef \| null`, `timeline: TraceItem[]`), `TraceItem` (`at`, `stage`, `event`, `detail: Record<string, unknown>`), `BotRef` (`botId`, `platformUserId`). |
| `console/src/pages/TracePage.tsx` | Página: campo "Colar txid / idempotencyKey / externalReference" + botão Buscar; `useAsync` + `api.get<TransactionTraceResponse>('/api/v1/master/trace/transactions/${ref}')`; cabeçalho (Card com valor/status/merchantId/provider/profileId/datas/bot/traceId) + timeline vertical (`TraceItem` por linha: timestamp `formatDate`, badge de stage colorido, evento, detail como chave-valor); 404 → banner "transação não encontrada"; erro genérico → `ErrorBanner`. |
| `console/src/App.tsx` | Rota `/console/trace` → `<TracePage />` no grupo privado. |
| `console/src/components/Layout.tsx` | Item no `NAV_ITEMS` (seção "Operação" — junto de dashboard/transações; visível para ROLE_MASTER). |
| `console/src/styles.css` | Classes `.trace-timeline`, `.trace-item`, `.trace-stage-badge` + cores por stage (BOT, GATEWAY, PSP, LEDGER, WEBHOOK) via variáveis CSS. |

Detalhes da página (padrão UX do console, ex. `LookupPage`):
- Input controlado + submit (Enter ou botão); trim do ref; validação de não-vazio.
- Estado inicial: hint de uso ("cole o txid do PSP, a idempotencyKey do bot ou o
  UUID interno").
- Timeline: ordem de cima (mais antigo) para baixo (mais recente); item com
  `detail` renderizado como lista de pares chave-valor (sem JSON cru, salvo
  valores complexos).
- O `traceId` no cabeçalho tem botão de copiar (`copyToClipboard` — existe no
  `ui.tsx`).

## D7 — Arquivos previstos

**Backend:**
- Novo: `src/main/resources/db/migration/V74__pix_transactions_trace_id.sql`
- Modificados: `PixTransaction` (getter), `PixTransactionJpaEntity` (campo+get/set),
  `PixTransactionMapper` (setTraceId), `TransactionTraceResponse` (campo),
  `PixChargePersistence` (data.traceId), `ProcessPixWebhookService` (data.traceId),
  `NotificationProcessor` (MDC)

**Frontend (`console/`):**
- Modificados: `src/types.ts`, `src/App.tsx`, `src/components/Layout.tsx`,
  `src/styles.css`
- Novo: `src/pages/TracePage.tsx`

**Testes:**
- `PixTransactionMapper` (ou verifies existentes): `toEntity` seta traceId = id.
- `MasterTraceControllerTest`/`TransactionTraceServiceTest` (Spec 125): response
  contém `traceId`.
- `ProcessPixWebhookServiceTest`: payload do SALE_PAID contém `traceId`.
- `PixChargePersistence` (teste existente, se houver): CHARGE_CREATED com traceId.
- `NotificationProcessorTest`: evento TRANSACTION → MDC `traceId` setado durante
  o process e removido após; evento sem referência → MDC não setado.
- Frontend: `npx tsc --noEmit` + `npm run build` limpos (verificação — sem lib
  de testes no console).

## D8 — Riscos e mitigação

- **Coluna NOT NULL em tabela populada** → backfill na MESMA migration antes do
  ALTER (ordem acima). Flyway roda em transação única (Postgres DDL transacional).
- **MDC vazando entre processamentos** (thread do pool) → `finally` com remove;
  teste cobre (processar evento com referência e depois sem → MDC limpo).
- **Cardinalidade/JSON do detail na UI** → renderização chave-valor simples;
  valores complexos caem em `<pre>`.
- **Fase 4 sem testes automatizados** → typecheck + build + smoke manual
  documentado no task.md (aceito — console não tem vitest/jest).
