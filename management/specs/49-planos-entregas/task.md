# Spec 49 — Task breakdown

Status: `audited` (2026-08-05). Ciclo SDD completo.

## Implementação

- [x] **Migration V37** (o design previa "V36 parte 2", mas a V36 já foi
      consumida pelo storefront): `plans`, `plan_delivery_actions`,
      `plan_deliveries` (com `platform` + `platform_user_id` criptografado e
      `buyer_id_hash`), `plan_delivery_actions_exec`,
      `pix_transactions.plan_id`/`platform`/`platform_user_id` + índices/unique.
- [x] **Domínio**: `Plan`, `DeliveryAction`/`DeliveryActionType`,
      `PlanDelivery`/`PlanDeliveryStatus`, `DeliveryActionExec`/
      `DeliveryActionStatus` (complete com gate; allDone → DELIVERED;
      FAILED → entrega FAILED; retry reabre FAILED como PENDING).
- [x] **Portas**: PlanRepository, PlanDeliveryActionRepository,
      PlanDeliveryRepository, PlanDeliveryActionExecRepository.
- [x] **PixTransaction com planId + platform/platformUserId** (criptografados)
      (+ CreatePixChargeService propagando).
- [x] **Planos (portal)**: `PlanService` + `PortalPlanController`
      (GET/POST/PATCH, activate/deactivate, CRUD de ações com reordenação,
      payload validado por tipo e criptografado; listagem sem payload — só
      `hasDeliveryActions`).
- [x] **Compra (nyx)**: `PlanPurchaseService` + `POST /api/v1/foxcash/plans/{id}/purchase`
      — `platform`/`platformUserId` obrigatórios (400 se ausentes/inválidos);
      persiste na transação.
- [x] **Entrega**: `DeliveryRecorder` no `ProcessPixWebhookService` (APPROVED +
      planId) — entrega PENDING com `platform`/`platform_user_id` + ações
      PENDING (snapshot); corrida → reutiliza; plano sem ação → DELIVERED direto.
- [x] **Consulta/execução (nyx)**: `DeliveryQueryService` (pending/
      by-transaction, 404 cross-loja; devolve `platformUserId` ao bot) +
      `DeliveryActionCompletionService`
      (`POST .../deliveries/{id}/actions/{seq}/complete`, `POST .../retry`).
- [x] **Atividade/logs**: `PlatformAction` += PLAN_CREATED/PLAN_PURCHASED/
      DELIVERY_ACTION_COMPLETED/DELIVERY_COMPLETED; payloads e platformUserId
      mascarados nos logs (SensitiveDataLoggingFilter).
- [x] **Storefront**: `GET /api/v1/foxcash/storefront` agora devolve os planos
      ATIVOS (card visual — sem payload de ações, nunca em claro ao público).
- [x] **Rate limit**: `POST /api/v1/foxcash/plans/{id}/purchase` no `RateLimitFilter`
      (Spec 43, por IP).
- [x] **Testes**: domínio (transições/gate/retry), PlanService (validação por
      tipo, reordenação, sem payload na listagem), PlanPurchase (idempotência,
      plano inativo), DeliveryRecorder (replay/corrida/sem-ação), Completion
      (gate/replay/FAILED→retry→DONE→DELIVERED, 404 cross-loja), controllers;
      E2E cenário 24 (plano 3 ações → PAID → 3 PENDING → 1,2 DONE → 3 FAILED
      → entrega FAILED → retry → DONE → DELIVERED).

## Verificação

- [x] `mvn test` verde (**578/0/0** — 58 novos) e E2E **24/24** (cenário 24
      novo) sem regressão.
- [x] Smoke ao vivo: plano com ações → purchase → PAID → ações no banco
      (`v1:` no payload) → complete por ação → DELIVERED.

## Verificação (`/spec-verify`, 2026-08-05)

- [x] Consistência verificada: purchase exige `platform`/`platformUserId`
      (persistidos criptografados na transação e na entrega); entrega com
      progresso por ação (snapshot) + retry; bots stateless (destinatário
      centralizado no backend).
- [x] Achados aplicados: `POST /deliveries/{id}/retry` adicionado aos critérios
      de aceite (faltava no requirements; já estava no design/task). Status →
      `verified`.

## Auditoria do ciclo SDD (2026-08-05)

- [x] Critérios de aceite verificados ao vivo (smoke + E2E 24/24).
- [x] Achados corrigidos: **2** (encontrados no smoke ao vivo) — (1)
      `plan_delivery_actions.created_at` NULL (o `DeliveryAction` não carregava
      timestamp; a coluna é NOT NULL) → `createdAt` no domínio + mapper;
      (2) `findByStoreIdAndStatusOrderByCreatedAtAsc(String)` não vinculava
      (parâmetro String vs enum JPA) → `PlanDeliveryStatus` no repositório.
      Aceitos por design: payload/`platformUserId` devolvidos em claro apenas
      ao dono (criptografados em repouso); `StoreRequest`/`PlanRequest` sem Bean
      Validation (validação no domínio — PATCH parcial).
- [x] Status → `audited`.

## Adendo pós-auditoria (2026-08-07, commit `8ce706f`) — lock pessimista na conclusão

Bug exposto pelo E2E `concurrency.spec.js` cenário 4 (replay concorrente): o
gate de status era **em memória** — 2 threads liam a ação `PENDING` e a
executavam **2×** (2 registros de auditoria). Correção:

- [x] `PlanDeliveryActionExecJpaRepository.findByDeliveryIdOrderBySeqForUpdate`
      (`SELECT ... FOR UPDATE`) — serializa as conclusões da MESMA entrega.
- [x] `DeliveryActionCompletionService` usa o lock no complete **e no retry**:
      o 2º request bloqueia, relê a ação já concluída e vira **replay
      idempotente** (204) → **1 execução, 1 auditoria** `DELIVERY_ACTION_COMPLETED`.
- [x] `DeliveryActionCompletionServiceTest` atualizado (+ cenários do lock).
- [x] `e2e-tests/tests/concurrency.spec.js` — 7 invariantes de concorrência
      (compra idempotente, webhook PAID → 1 crédito, entregas simultâneas sem
      misturar ações, **replay → 1 execução**, falha parcial → retry,
      append-only, IDOR cross-loja).

**Verificação (2026-08-07):** `mvn test` **1094/0/0**; E2E concurrency **7/7**
(3.8s); k6 `concurrency.js` (50 VUs simultâneas) 150/150 checks, p95 **451ms**
(< 1000ms), 0% erros; k6 `critical-endpoints.js` (30 VUs, 40s) **16.315 checks
100%**, webhook PAID concorrente → **204 sempre**, p95 9.2ms, e no banco o
lojista k6 ficou com **exatamente 1 SALE_CREDIT e 1 delivery** (0 duplicações).

> **Nota:** a Spec 49 permanece `audited` — este adendo documenta o hardening
> pós-auditoria sem reabrir o ciclo SDD.
