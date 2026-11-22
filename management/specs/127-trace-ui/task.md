---
name: Trace de transação — traceId propagado (fase 3) + UI no console master (fase 4)
status: created
---

# Spec 127 — Task: Trace de transação (traceId + UI)

Ordem: T0 (migration) → T1 (model/entity/mapper) → T2 (response + payload do
outbox) → T3 (MDC no worker) → T4 (UI console) → T5 (testes/verificação) → T6
(auditoria). Nenhuma mudança de domínio financeiro — observabilidade apenas.

## T0 — Migration (critério C1)

- [x] T0.1: **`V74__pix_transactions_trace_id.sql`** — `ADD COLUMN trace_id UUID`
      → `UPDATE pix_transactions SET trace_id = id WHERE trace_id IS NULL` →
      `ALTER COLUMN trace_id SET NOT NULL`. Confirmar V74 livre (V73 usado pela
      Spec 125) e rodar `mvn test` (o boot aplica a migration — testes de
      contexto validam).

## T1 — traceId na camada de domínio/infra (C1)

- [x] T1.1: `PixTransaction` — getter `traceId()` → `id` + Javadoc. SEM campo
      novo (os overloads de create/restore não são tocados).
- [x] T1.2: `PixTransactionJpaEntity` — campo `traceId`
      (`@Column(name = "trace_id", nullable = false)`) + getter/setter. SEM
      mudar construtores.
- [x] T1.3: `PixTransactionMapper.toEntity` — `entity.setTraceId(transaction.traceId())`
      (bloco do provider/profileId). `toDomain` sem mudança.

## T2 — traceId no response e no payload do outbox (C2, C3)

- [x] T2.1: `TransactionTraceResponse` — campo `traceId` no record + preenchido
      no `from(...)` (`tx.traceId()`).
- [x] T2.2: `PixChargePersistence.attachAndPublish` — `CHARGE_CREATED` data ganha
      `"traceId", saved.id()`.
- [x] T2.3: `ProcessPixWebhookService.publishSaleEvent` — data ganha
      `"traceId", transaction.id()` (SALE_PAID/SALE_REFUNDED).

## T3 — MDC traceId no worker (C4)

- [x] T3.1: `NotificationProcessor.process` — `traceIdOf(notification)`
      (`entityType == "TRANSACTION"` → `entityId`); `MDC.put` antes do corpo e
      `MDC.remove` no `finally` (sem vazamento na thread do pool). Eventos sem
      referência não setam o campo.
- [x] T3.2: Confirmar logback/axiom já emitem o campo (verificado no design D5 —
      `%X{traceId:-}` no padrão estruturado + `includeMdc=true` no Axiom) — sem
      mudança de logback.

## T4 — UI no Master Console (C5)

- [x] T4.1: `console/src/types.ts` — `TransactionTraceResponse`, `TraceItem`,
      `BotRef` (espelham o backend, com `traceId`).
- [x] T4.2: `console/src/pages/TracePage.tsx` — input (colar ref) + Buscar;
      `api.get<TransactionTraceResponse>(...)` via `useAsync`; cabeçalho (Card) +
      timeline vertical com stage colorido + detail chave-valor; estados:
      loading (Spinner), 404 ("transação não encontrada"), erro genérico
      (ErrorBanner); `traceId` com botão copiar.
- [x] T4.3: `console/src/App.tsx` — rota `/console/trace` no grupo privado.
- [x] T4.4: `console/src/components/Layout.tsx` — item no `NAV_ITEMS`.
- [x] T4.5: `console/src/styles.css` — `.trace-timeline`/`.trace-item`/
      `.trace-stage-badge` + cores por stage.

## T5 — Testes e verificação (C6)

- [x] T5.1: Backend — `PixTransactionMapperTest` (ou verifies existentes):
      `toEntity` seta traceId = id.
- [x] T5.2: `TransactionTraceServiceTest`/`MasterTraceControllerTest` (Spec 125):
      response contém `traceId`.
- [x] T5.3: `ProcessPixWebhookServiceTest`: payload do SALE_PAID contém
      `traceId`.
- [x] T5.4: `NotificationProcessorTest`: evento `TRANSACTION` → MDC `traceId`
      setado durante o process e ausente após; evento sem referência → MDC não
      setado (e não vaza para o próximo process).
- [x] T5.5: Frontend — `npx tsc --noEmit` e `npm run build` (vite) limpos
      (verificação — console sem lib de testes).
- [x] T5.6: `mvn test` completo verde — **1973/0/0** (1969 + 4 novos: mapper 1,
      MDC 3).

## T6 — Auditoria

- [x] T6.1: Verificação critério a critério (C1–C6) + docs atualizados
      (status `implemented`/`audited` no roadmap e specs).
- [x] T6.2: Smoke manual da página (documentar no task.md): colar txid real →
      timeline renderiza; 404 → banner.

## §Auditoria (2026-08-16)

**`/spec-audit 127`: 6/6 critérios OK — 0 achados de código.** Verificação
critério a critério (ao vivo contra app dev + banco real, além dos testes):

- **C1 (trace_id persistido)** — migration V74 aplicada no boot (2 boots limpos);
  `PixTransaction.traceId()` derivado do `id`; mapper seta `trace_id` em todo
  save. **Ao vivo**: `SELECT ... WHERE id=tx` → `trace_id == id` (t). Teste:
  `PixTransactionMapperTest` (novo).
- **C2 (traceId no response)** — `TransactionTraceResponse.traceId`. **Ao vivo**:
  trace por transactionId / externalReference / idempotencyKey (os 3 lookups da
  Spec 125) → `traceId == transactionId`, timeline com WEBHOOK/GATEWAY (cobrança
  PENDING). Teste: `MasterTraceControllerTest`.
- **C3 (data.traceId no payload do outbox)** — CHARGE_CREATED (attachAndPublish)
  + SALE_PAID/SALE_REFUNDED (publishSaleEvent). **Ao vivo**: payloads no
  `notification_outbox` de CHARGE_CREATED e SALE_PAID contêm
  `"traceId":"<txid>"` em `data`. Testes: `PixChargePersistenceTest`,
  `ProcessPixWebhookServiceTest`.
- **C4 (MDC traceId no worker)** — `NotificationProcessor.process` seta
  `MDC.put("traceId", entityId)` para `entityType == "TRANSACTION"` e remove no
  `finally`. Logback prod emite `traceId=%X{traceId:-}` (logback-spring.xml L23)
  + Axiom `includeMdc=true` (L50) — correlação ponta a ponta. **Ao vivo**: o
  worker entregou SALE_PAID (log `[nyx-dispatch-worker]`); o MDC é provado pelos
  3 testes novos (setado durante / ausente após / sem vazamento entre
  processamentos) — o padrão dev é texto legível sem MDC (esperado).
- **C5 (página no Master Console)** — `TracePage.tsx` (`/console/trace`): input
  colar ref + Buscar via `useAsync`; cabeçalho (traceId + copiar); timeline
  vertical com badge de stage colorido + detail chave-valor; loading/404/erro.
  **Ao vivo**: flag `NYXCASH_CONSOLE_SERVE_FRONTEND` OFF → `/console/trace` **401**
  (sem SPA exposta — invariante de segurança); flag ON → **200 text/html** com o
  `index.html` do console (bundle `index-CNlSdPzj.js` contém "Trace de transação"
  e "/console/trace"). SPA fallback no `ConsoleSpaController` +
  `SecurityConfig.spaPublicPaths()` (teste `ConsoleSpaControllerTest`).
- **C6 (verificação)** — `mvn test` **1973/0/0** re-executado; `npx tsc --noEmit`
  + `npm run build` limpos (console 67 módulos).

**Smoke ao vivo completo (T6.2)**: lojista criado → cobrança PENDING (mock PSP)
→ trace por 3 tipos de ref (traceId == transactionId, timeline
GATEWAY/WEBHOOK) → webhook PAID → **APPROVED** com jornada completa
(GATEWAY CHARGE_CREATED → LEDGER SALE_CREDIT → PSP SALE_PAID → WEBHOOK
SALE_PAID com status/attempts). Dados de smoke ficaram no banco dev local
(lojista `Loja Smoke Spec127` + 1 transação — inócuos; o global-setup do E2E
trunca).

**0 achados abertos.** Fim do ciclo SDD — Spec 127 `audited` (roadmap +
requirements/design/task). Fase 5 (autoatendimento do lojista) registrada como
futura (fora de escopo).

## §Implementação (2026-08-16)

T0–T5 concluídas — **status `implemented`** (aguardando `/spec-audit`):

- **Fase 3 (backend)** — V74 aplicada; `PixTransaction.traceId()` (getter derivado do
  `id`, sem campo novo); `PixTransactionJpaEntity.trace_id` (NOT NULL) +
  `PixTransactionMapper.toEntity` seta o traceId (= id); `TransactionTraceResponse.traceId`
  no record + `from(...)`; `data.traceId` em `PixChargePersistence.attachAndPublish`
  (CHARGE_CREATED) e `ProcessPixWebhookService.publishSaleEvent` (SALE_PAID/SALE_REFUNDED);
  **MDC `traceId` no `NotificationProcessor.process`** (`traceIdOf`: `entityType ==
  "TRANSACTION"` → `entityId`; `MDC.put` antes do corpo + `MDC.remove` no `finally` —
  sem vazamento na thread do pool; eventos sem referência não setam). Logback/Axiom já
  emitem `traceId=%X{traceId:-}` — zero mudança de config.
- **Fase 4 (UI)** — `console/src/pages/TracePage.tsx` (input "colar ref" + Buscar via
  `useAsync`; cabeçalho com traceId + botão copiar; timeline vertical com badge de stage
  colorido e detail chave-valor; loading/404/erro genérico); tipos em `types.ts`; rota
  `/console/trace` em `App.tsx`; item "Trace" no `NAV_ITEMS` (seção Operação); CSS
  `.trace-timeline`/`.trace-item`/`.trace-stage-*`; **SPA fallback**: `/console/trace`
  adicionado ao `ConsoleSpaController` + `SecurityConfig.spaPublicPaths()` (+ teste
  `ConsoleSpaControllerTest` atualizado — lição Spec 80/84: full reload da rota).
- **Testes** — `PixTransactionMapperTest` (novo, 1), `NotificationProcessorTest` (+3 MDC),
  `MasterTraceControllerTest` (traceId no response), `ProcessPixWebhookServiceTest`
  (payload SALE_PAID com traceId), `PixChargePersistenceTest` (payload CHARGE_CREATED
  com traceId). `mvn test` **1973/0/0** (1969 + 4). Frontend: `npx tsc --noEmit` +
  `npm run build` limpos (console 67 módulos).
- **Desvios** — nenhum de contrato. Fase 5 (autoatendimento do lojista) registrada como
  futura (fora de escopo).

## Fases futuras registradas (fora de escopo)

- **Fase 5 — autoatendimento do lojista**: expor ao lojista o trace da PRÓPRIA
  transação (filtro por `merchantId` + escopo do perfil — Spec 124), endpoint
  portal + UI no portal. Candidato a nova spec quando o portal rico (Specs
  101–119) estiver em curso.
