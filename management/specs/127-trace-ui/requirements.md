---
name: Trace de transação — traceId propagado (fase 3) + UI no console master (fase 4)
status: audited
references:
  - "management/vision.md#V94"
  - "management/specs/125-trace-transacao/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/41-atividade-plataforma/requirements.md"
---

# Spec 127 — Trace de transação: traceId + UI no console (fases 3–4 da Spec 125)

## O que entrega

A Spec 125 entregou o **endpoint** de trace (`GET
/api/v1/master/trace/transactions/{ref}`, fases 1–2): dado o txid/`idempotencyKey`/
`externalReference`, o suporte vê a jornada completa (Bot → Gateway → PSP →
Ledger → Webhook). Faltam duas peças:

1. **Correlação de logs (fase 3)** — o `RequestIdFilter` (Spec 18) põe
   `requestId` no MDC dos logs HTTP, mas **o worker do outbox
   (`NotificationProcessor`) roda em thread própria SEM requestId**: quando o
   suporte investiga "a venda pagou mas o webhook do lojista não saiu", não há
   como correlacionar o log do request do bot, o log do webhook do PSP e o log
   do worker — são três fluxos sem fio comum.
2. **Tela de suporte (fase 4)** — o endpoint existe, mas o suporte usa `curl`;
   não há página no Master Console para "colar o txid e ver a jornada".

Esta spec fecha as duas:

- **Fase 3 (backend, pequeno)** — `trace_id` de negócio em `pix_transactions`
  (migration V74) + `traceId` no `TransactionTraceResponse` + `data.traceId` no
  payload do outbox + **MDC `traceId` no `NotificationProcessor`** (o worker
  loga com o traceId do evento — o log estruturado já emite
  `traceId=%X{traceId:-}` e o Axiom inclui MDC: correlação ponta a ponta).
- **Fase 4 (UI)** — página "Trace de transação" no Master Console
  (`/console/trace`): colar txid/`idempotencyKey`/`externalReference` → renderiza
  a jornada (reusa `TransactionTraceResponse`) com timeline de stage colorido.

Sem mudança de fluxo de dinheiro. A fase 5 (lojista vê o próprio trace —
autoatendimento de suporte) fica registrada como futura, fora de escopo.

## Critérios de aceitação

**C1 — `trace_id` persistido (V74).** `pix_transactions` ganha a coluna
`trace_id UUID NOT NULL`, com **backfill `= id`** para as transações existentes
(o traceId de negócio de uma cobrança É o seu `id` — estável do nascimento à
morte; a coluna existe para o vínculo canônico explícito). O model
`PixTransaction` expõe `traceId()` (derivado do `id`, sem campo novo); o mapper
JPA persiste `entity.setTraceId(transaction.traceId())` em todo save.

**C2 — `traceId` no response do trace.** `TransactionTraceResponse` expõe
`traceId` (campo novo no record, preenchido do model) — a UI e o suporte vêem o
fio condutor de logs da transação.

**C3 — `data.traceId` no payload do outbox.** Os eventos de transação publicados
no outbox carregam `traceId` explícito no `data`: `CHARGE_CREATED`
(`PixChargePersistence.attachAndPublish`) e `SALE_PAID`/`SALE_REFUNDED`
(`ProcessPixWebhookService.publishSaleEvent`). O contrato do payload fica
auto-documentado (o receptor não precisa saber que traceId == transactionId).

**C4 — MDC `traceId` no worker do outbox.** `NotificationProcessor.process`
seta `MDC.put("traceId", ...)` quando o evento referencia uma transação
(`entityType == "TRANSACTION"` → `entityId`) e remove no `finally`. O log
estruturado (prod) e o drain do Axiom (Spec 95, `includeMdc=true`) já capturam o
campo — os logs do worker passam a ser correlacionáveis com o request do bot e o
webhook do PSP pelo MESMO traceId. Eventos sem referência (OUTBOX_DEAD,
JOB_FAILED) não setam o campo (sem poluição).

**C5 — Página "Trace de transação" no Master Console.** Rota
`/console/trace` (grupo privado, ROLE_MASTER — padrão do console): campo
"colar txid/`idempotencyKey`/`externalReference`" → busca
`GET /api/v1/master/trace/transactions/{ref}` → renderiza cabeçalho (valor,
status, merchantId, provider, profileId, datas, bot, **traceId**) + **timeline
vertical com stage colorido** (BOT/GATEWAY/PSP/LEDGER/WEBHOOK) com timestamp,
evento e detail. Estados: loading (spinner), 404 ("transação não encontrada"),
erro genérico (banner). Item na navegação do Layout.

**C6 — Verificação.** Backend: mapper persiste `traceId`; response do trace
contém `traceId`; payload do outbox contém `data.traceId`; `NotificationProcessor`
seta o MDC (e limpa no finally). `mvn test` verde. Frontend: `tsc --noEmit` +
`vite build` limpos (o console não tem lib de testes — verificação da UI por
typecheck + build + smoke manual documentado).

## Fora de escopo

- **Fase 5** — acesso do lojista ao trace da própria transação (autoatendimento;
  registrado como futura no task.md).
- Mudança de fluxo de dinheiro (nenhuma alteração de domínio financeiro).
- Testes automatizados de UI (console sem vitest/jest — a verificação é
  typecheck + build; testes de componentes ficam para uma fase futura).

## Dependências

- **Spec 125** (endpoint + `TransactionTraceResponse` + referência do outbox —
  `entityType`/`entityId` já existem no `OutboxNotification`).
- **Spec 35** (outbox — payload `WebhookEnvelope`, worker `NotificationProcessor`).
- **Spec 18** (`RequestIdFilter`/MDC + padrão do log estruturado com
  `%X{traceId:-}`).
- **Spec 95** (log drain — Axiom com `includeMdc`).
