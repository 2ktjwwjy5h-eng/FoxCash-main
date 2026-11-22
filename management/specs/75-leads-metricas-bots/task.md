---
name: Leads e métricas por bot (backend)
status: audited
---

# Spec 75 — Task: Leads e métricas por bot

## F0 — Domínio + migration (critérios 1–3)

- [x] T0.1: Migration `V50__bot_leads_and_funnel.sql` (D2.1) — **checar o
      próximo número livre** (V49 é reservada pela Spec 74). Tabelas
      `bot_leads` (UNIQUE bot_id+platform_user_id) e `bot_funnel_events`
      (CHECK event) + índices. ✅ — `bot_leads` (UNIQUE `(bot_id,
      platform_user_id)`, FK `store_bots` ON DELETE CASCADE), `bot_funnel_events`
      (CHECK event IN lead/checkout_started/paid, índice `(bot_id,
      occurred_at)`) + `pix_transactions.bot_id` anulável (T1.4).
- [x] T0.2: `BotLeadJpaEntity`/`BotLeadJpaRepository`/`BotLeadMapper` +
      `BotFunnelEventJpaEntity`/`BotFunnelEventJpaRepository`/mapper (D2.2).
- [x] T0.3: Portas `BotLeadRepository`/`BotFunnelEventRepository` + adapters
      JPA.
- [x] T0.4: Domínio `BotLead` (registerIfAbsent), enum `FunnelEvent`, value
      object `BotMetrics` (D3).
- [x] T0.5: Testes — UNIQUE idempotente (insert duplicado → no-op), migration
      aplica limpa. ✅ — `BotLeadServiceTest`/`BotFunnelServiceTest` cobrem a
      idempotência.

## F1 — Funil (critérios 4–6)

- [x] T1.1: `BotLeadService.recordLead(botId, platformUserId)` (D4) —
      idempotente.
- [x] T1.2: `BotFunnelService.recordCheckout` + `recordPaid` (D4) —
      `recordPaid` chamado **na mesma transação** do `PAID` (Spec 23);
      idempotente por `(bot_id, transaction_id)`. ✅ — `ProcessPixWebhookService`
      (`@Transactional`, case `APPROVED`) chama `recordPaid(transaction)` na
      mesma transação do crédito (replay do webhook não duplica — E2E passo 4).
- [x] T1.3: `purchase` (Spec 49) aceita `botId` opcional (D5): validação de
      propriedade (404) e status ACTIVE (409 `bot_inactive`); grava
      `pix_transactions.bot_id` + `recordCheckout`; sem `botId` → zero
      regressão. ✅ — `PlanPurchaseService`: `findByIdAndMerchantId` → 404
      cross-lojista sem revelar existência; 409 `bot_inactive`; `bot_id`
      atribuído + `recordCheckout` na mesma transação.
- [x] T1.4: Coluna `pix_transactions.bot_id` (anulável) incluída **na migration
      V50** (`V50__bot_leads_and_funnel.sql`) + mapeamento na entidade.
- [x] T1.5: Testes — purchase com/sem botId, 404 cross-lojista, 409 inativo,
      replay do webhook PSP não duplica `paid`. ✅ — `PlanPurchaseServiceTest`
      (casos botId) + `BotFunnelServiceTest`.

## F2 — Leads por plataforma (critérios 2–3)

- [x] T2.1: Endpoint público da Spec 74 (Telegram) chama `recordLead` com o
      `platform_user_id` do update (após validação de segredo/estado). ✅ —
      `TelegramWebhookController` (L155) chama `recordLead(bot.id(), userId,
      utm)`; replay do update não duplica (E2E passo 2).
- [x] T2.2: Gerenciador Discord (Spec 74 D6) chama `recordLead` na interação.
      ✅ — `DiscordBotManager` (L60).
- [x] T2.3: Testes — replay do update não duplica lead; interação Discord
      registra lead. ✅ — `BotLeadServiceTest`/`DiscordBotManagerTest`.

## F3 — Métricas (critérios 7–8)

- [x] T3.1: `BotMetricsService.summarize`/`summarizeMerchant`/`summarizeAll`
      (D4/D7) — venda = `PAID` não estornado; `receita` em escala normalizada.
      ✅ — vendas/receita contam apenas transações `APPROVED` (estornado vira
      `REFUNDED` e sai da conta — Specs 23/29).
- [x] T3.2: `PortalBotMetricsController` — `GET /api/v1/portal/bots/{id}/metrics`
      e `GET /api/v1/portal/bots/metrics` (escopo por lojista, 404
      cross-lojista).
- [x] T3.3: `MasterBotMetricsController` — `GET /api/v1/master/bots/metrics`
      (ROLE_MASTER, read-only, RBAC Spec 69).
- [x] T3.4: Testes — contagens/agregados, período default 30d, estornado não
      conta, sem PII no response. ✅ — `BotMetricsServiceTest`.

## F4 — Fechamento (critérios 9–10)

- [x] T4.1: E2E do funil com Telegram/Discord mockados (lead → purchase →
      PAID → métrica). ✅ — `e2e-tests/tests/bot-funnel.spec.js` **6/6**
      (2026-08-17): setup → lead (replay não duplica) → checkout (replay da
      chave não duplica) → paid (replay do webhook não duplica) → métricas do
      portal → espelho master (RBAC Spec 69).
- [x] T4.2: `mvn test` verde sem regressão. ✅ — **2061/0/0** (2026-08-17).
- [x] T4.3: `/spec-verify 75` e depois `/spec-audit 75` (critérios 1–10). ✅ —
      auditoria 2026-08-17: **10/10 critérios OK, 0 achados de código** (detalhe
      no §Auditoria abaixo).

## §Auditoria (`/spec-audit 75`)

**10 critérios do requirements verificados um a um contra o código; 0 achados
de código; 2 achados de DOC corrigidos (task.md todo desmarcado + status
`verified` stale — ver §Correções de doc).**

1. **Migration** — `V50__bot_leads_and_funnel.sql`: `bot_leads` (UNIQUE
   `(bot_id, platform_user_id)`, FK `store_bots` ON DELETE CASCADE) +
   `bot_funnel_events` (CHECK event IN lead/checkout_started/paid, índice
   `(bot_id, occurred_at)`) + `pix_transactions.bot_id` anulável (T1.4). A
   correção `V53__fix_bot_funnel_event_check.sql` alinha o CHECK ao enum
   UPPERCASE (`LEAD`/`CHECKOUT_STARTED`/`PAID` — `EnumType.STRING`): o CHECK
   original em minúsculas quebrava todo INSERT do funil (500 na compra com
   botId) — bug real descoberto pelo E2E. ✅
2. **Registro de lead (Telegram)** — `TelegramWebhookController` (L155) chama
   `recordLead(bot.id(), userId, utm)` após a validação de segredo/estado;
   idempotente (UNIQUE + catch no serviço) — replay do update não duplica
   (E2E passo 2). ✅
3. **Registro de lead (Discord)** — `DiscordBotManager` (L60) chama
   `recordLead` na chegada da interação. ✅
4. **`purchase` com `botId`** — `PlanPurchaseService` (L77–94): `botId`
   opcional; `findByIdAndMerchantId` → 404 cross-lojista sem revelar
   existência; status ≠ ACTIVE → 409 `bot_inactive`; grava
   `pix_transactions.bot_id` (L91) + `recordCheckout` (L94) na mesma
   transação; sem `botId` → comportamento atual intacto (zero regressão). ✅
5. **Evento `paid`** — `ProcessPixWebhookService` (`@Transactional` L74, case
   `APPROVED` L95) chama `recordPaid(transaction)` (L114) **na mesma
   transação** do crédito do wallet; idempotente por `(bot_id,
   transaction_id)` — replay do webhook não duplica (E2E passo 4). ✅
6. **Estorno não desfaz lead, mas desfaz venda** — `BotMetricsService`
   (L27, L91–101): vendas/receita só contam transações `APPROVED`; transação
   estornada vira `REFUNDED` e sai da conta automaticamente (Specs 23/29); o
   evento `paid` permanece como histórico. ✅
7. **Métricas — portal** — `PortalBotMetricsController`:
   `GET /api/v1/portal/bots/{id}/metrics` (L46) +
   `GET /api/v1/portal/bots/metrics` (L63), escopo por lojista (404
   cross-lojista); período default 30d; conversão = vendas/leads. ✅
8. **Métricas — master** — `MasterBotMetricsController`:
   `GET /api/v1/master/bots/metrics` (ROLE_MASTER, espelho read-only, RBAC
   Spec 69). ✅
9. **Sem regressão** — `purchase` sem `botId` mantém contrato (param opcional);
   `mvn test` **2061/0/0** (2026-08-17); E2E `bot-funnel.spec.js` **6/6**
   (2026-08-17, mock Telegram porta 9001 — lead→checkout→paid→métricas
   portal→espelho master). ✅
10. **Auditoria** — nenhuma ação administrativa nova (métricas são leitura;
    `PlatformAction`/Spec 41 sem escrita nova nesta spec); os eventos de funil
    são gravados pelo fluxo. ✅

**Testes citados:** `BotLeadServiceTest`, `BotFunnelServiceTest`,
`BotMetricsServiceTest`, `PlanPurchaseServiceTest` (casos botId),
`DiscordBotManagerTest`, `mvn test` 2061/0/0, E2E `bot-funnel.spec.js` 6/6.

**§Correções de doc:** o `task.md` estava inteiro desmarcado `[ ]` com status
`verified` (stale) apesar da implementação completa; o `requirements.md` e o
roadmap diziam `implemented`. Todas as tasks foram marcadas com a evidência
real e os status foram alinhados para `audited` (requirements.md, task.md e
roadmap.md) em 2026-08-17. Nenhuma correção de código foi necessária.

Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
