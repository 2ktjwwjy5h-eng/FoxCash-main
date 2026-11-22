---
name: Trace de transação — rastreio ponta a ponta (bot → gateway → PSP → ledger → webhook)
status: implemented
---

# Spec 125 — Task: Trace de transação

Ordem: T0 (migration/fundamento) → T1 (query activity por entidade) → T2
(service de trace) → T3 (controller + DTO) → T4 (fase 2: outbox referenciado)
→ T5 (testes) → T6 (auditoria). Núcleo = fases 1–2 do requirements; fases 3–4
(traceId propagado, UI) ficam como tarefas futuras registradas no task.md.

## T0 — Migration (critérios C4, C5)

- [x] T0.1: **`V73__trace_indexes.sql`** — `ix_platform_activity_entity`
      (`platform_activity_log(entity_type, entity_id, created_at)`). Fase 2:
      `ALTER TABLE notification_outbox ADD entity_type/entity_id` + índice
      `ix_outbox_entity` + **backfill** (parse do `payload` JSON →
      `data.transactionId` para `CHARGE_CREATED`/`SALE_PAID`/`SALE_REFUNDED`).
      Confirmar V73 livre na implementação (V72 usado pelas Specs 97/98).

## T1 — Activity filtrável por entidade (C4)

- [x] T1.1: `PlatformActivityQuery` += `entityType` (String) e `entityId` (UUID),
      ambos opcionais; `PlatformActivityRepository.query` aplica os filtros.
- [x] T1.2: Adapter JPA — `@Query` com os novos predicados (Specification ou
      JPQL `AND (:entityType IS NULL OR a.entityType = :entityType)` — seguir o
      padrão do projeto; atenção ao "IS NULL OR quebra no Postgres" (achado
      Spec 89 §1 — usar COALESCE/parametrizado corretamente)).
- [x] T1.3: Testes do filtro (por entityId retorna só os eventos da entidade).

## T2 — Service de trace (C2)

- [x] T2.1: `TransactionTraceService` — resolve `ref` (D2: UUID → externalRef →
      idempotencyKey; 404 se nada) e agrega por `transaction.id`:
      activity (entityId), wallet_ledger (reference), master_ledger (reference),
      funil (bot_funnel_events por transactionId).
- [x] T2.1a: **Queries novas de repositório** (verificação 2026-08-16):
      `WalletLedgerRepository`/`MasterLedgerRepository` — `findByReferenceTypeAndReferenceId(
      referenceType, referenceId)` (o `findByReference` existente exige
      merchantId+type e retorna Optional — não serve para listar TODAS as
      entries de uma transação); `BotFunnelEventRepository` —
      `findByTransactionId(transactionId)` (hoje só existe
      `findByBotIdAndTransactionId`; o botId da tx permitiria reusar, mas a
      query direta é mais simples). Adapter JPA + testes.
- [x] T2.2: Merge da timeline (D5) — `TraceItem(at, stage, event, detail)`,
      ordenado por `at` + ordem canônica (BOT < GATEWAY < PSP < LEDGER <
      WEBHOOK). `CHARGE_CREATED`→GATEWAY; `SALE_PAID`/`SALE_REFUNDED`→PSP;
      `SALE_CREDIT`/`REFUND_DEBIT`→LEDGER; `CHECKOUT_STARTED`/`PAID`→BOT.
- [x] T2.3: Cabeçalho (D4) — tx + bot (botId/platformUserId) + profileId;
      `details` sempre a partir da fonte original (activity já mascarada).

## T3 — Controller + DTO (C1, C3)

- [x] T3.1: `MasterTraceController` — `GET /api/v1/master/trace/transactions/{ref}`
      (leitura → ROLE_MASTER via `SecurityConfig`, padrão `MasterPanelController`;
      sem `MasterPermission` nova — verificação 2026-08-16); 404 `TransactionNotFound`.
- [x] T3.2: `TransactionTraceResponse` (D4) — campos do cabeçalho + `timeline`.
- [x] T3.3: `SecurityConfig` — rota `/api/v1/master/trace/**` protegida
      (`hasRole("MASTER")`, cobre o padrão `/api/v1/master/**` já existente),
      fora das rotas públicas.

## T4 — Fase 2: entrega do webhook no trace (C5)

- [x] T4.1: `WebhookEventPublisher.publish(merchantId, type, data, entityType,
      entityId)` — overload preservando a assinatura atual; `ProcessPixWebhookService`
      passa `TRANSACTION`/`transaction.id` em `publishSaleEvent`;
      `CreatePixChargeService` idem no `CHARGE_CREATED`.
- [x] T4.2: `NotificationOutboxPort`/adapter — `savePending` com referência +
      `findByEntity(entityType, entityId)`.
- [x] T4.3: Timeline inclui `SALE_PAID_DELIVERED`/`CHARGE_CREATED_DELIVERED`
      com `status` real do evento (`PENDING`/`SENT`/`FAILED` — coluna
      `notification_outbox.status` já existe, verificação 2026-08-16) +
      `attempts`/`lastError`/`nextAttemptAt` (Spec 35).

## T5 — Testes (C6)

- [x] T5.1: `TransactionTraceServiceTest` — lookup por UUID/externalRef/key (3
      formas + 404); timeline completa (fluxo feliz com bot + plano); timeline
      parcial (sem bot → sem `BOT`); estorno (`SALE_REFUNDED` + `REFUND_DEBIT`);
      ordenação.
- [x] T5.2: `MasterTraceControllerTest` — 200 com timeline; 404; **403 sem
      ROLE_MASTER**; máscara (chave/destino nunca em claro no payload).
- [x] T5.3: Fase 2 — outbox com `attempts`/`lastError` aparece na timeline.
- [x] T5.4: `mvn test` completo verde.

## T6 — Auditoria

- [x] T6.1: `/spec-verify 125` (documentos consistentes) e `/spec-audit 125`
      (código vs. critérios C1–C6), idempotente até 0 achados. **(Concluído
      2026-08-16 — `/spec-audit 125`: 6/6 critérios OK, 0 achados de código, 0
      abertos. Verificação: C1 lookup 3 formas (`resolve()` chain UUID →
      externalReference → idempotencyKey) + 404 `TransactionNotFound`; C2
      timeline de fontes reais (activity/ledger×2/funil/outbox) com merge
      ordenado e BOT só se funil existir; C3 rota sob `/api/v1/master/**` →
      `hasRole("MASTER")` (SecurityConfig L161) + details mascarado na origem;
      C4 `PlatformActivityQuery` entityType/entityId + índice V73
      `(entity_type, entity_id, created_at)`; C5 outbox `entity_type`/
      `entity_id` + `attempts`/`lastError`/`nextAttemptAt` na timeline; C6
      testes `MasterTraceControllerTest` 5 + `TransactionTraceServiceTest` 6 =
      11/0/0 re-executados (suíte completa 1995/0/0). Fase 3 (trace_id V74) e
      Fase 4 (TracePage) foram entregues pela Spec 127 — núcleo + incrementais
      completos.)**

## Fases futuras registradas (fora do núcleo)

- **Fase 3 — traceId de negócio**: `trace_id` em `pix_transactions` (V74) +
  geração na criação + propagação no MDC do `NotificationProcessor` (o worker
  hoje roda sem requestId) + `traceId` no payload do webhook de saída.
- **Fase 4 — UI no console**: tela "colar txid → jornada" + (opcional) acesso
  do lojista ao próprio trace (filtro por `merchantId`).
