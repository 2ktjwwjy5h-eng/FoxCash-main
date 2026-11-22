---
name: Perfil de operação (fase 2) — filtro rígido de dados por perfil
status: audited
references:
  - "management/vision.md#V91"
  - "management/specs/122-operation-profile-backend/requirements.md"
  - "management/specs/123-operation-profile-ui/requirements.md"
  - "management/specs/91-fallback-adquirente/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/79-broadcast-motor/requirements.md"
  - "management/specs/81-tracking-eventos/requirements.md"
  - "management/specs/83-crm-chat/requirements.md"
---

# Spec 124 — Perfil de operação (fase 2): filtro rígido de dados por perfil

## O que entrega

O **"reset contextual"** dos módulos do portal: quando o lojista troca o **perfil
de operação** (Specs 122/123 — seletor no HeaderGlobal + header `X-Profile-ID`),
**cada módulo mostra apenas os dados do perfil ativo** — e as cobranças passam a
**rotear pelo gateway do perfil** (`profileId` no `RoutingPolicyResolver`, rota
viva). É a **fase 2** decidida pelo usuário (2026-08-15): filtro **rígido**
(esconde o que não pertence ao perfil), construída sobre a fundação da fase 1.

**Cadeia de resolução de perfil (fonte da verdade = banco, nunca header cego):**

| Dado | Resolução | Coluna |
|---|---|---|
| Bot | direta | `store_bots.profile_id` (fase 1, V69) |
| Transação Pix | direta (nova) | `pix_transactions.profile_id` (**V70**; gravada na criação; bot → perfil do bot; sem bot → default) |
| Leads / funil | derivada | `bot_leads.bot_id` / `bot_funnel_events.bot_id` → `store_bots.profile_id` |
| Broadcasts | derivada | `broadcasts.store_bot_id` → `store_bots.profile_id` |
| Chat / CRM | derivada | `chat_messages.bot_id` → `store_bots.profile_id` |
| Tracking | derivada | `tracking_profile_bots.bot_id` → `store_bots.profile_id` |
| Métricas de bot | derivada | por bot → `store_bots.profile_id` |

**Escopo por módulo (o que existe em código hoje — âncoras validadas):**

1. **Dashboard/KPIs** — `MerchantDashboardService`: faturamento, vendas
   aprovadas, conversão e séries temporais com `pix_transactions.profile_id =
   ativo`; "atenção" (leads) por bot-set do perfil.
2. **Vendas/Financeiro** — `MerchantSalesService`: extrato, métricas e detalhe
   com `profile_id = ativo`.
3. **CRM/Leads** — `CrmLeadService`: leads de `store_bots.profile_id = ativo`
   (join natural — `bot_leads` não tem `merchant_id`).
4. **Bots (frota)** — `StoreBotManagementUseCase` + `BotMetricsService`: só os
   bots do perfil ativo (as métricas somam a frota do perfil).
5. **Broadcasts** — `BroadcastQueryService`: broadcasts de bots do perfil.
6. **Tracking** — `TrackingProfileQueryService`: perfis de tracking cujos bots
   (`tracking_profile_bots.bot_id`) pertencem ao perfil ativo.
7. **Gateway (rota viva)** — `CreatePixChargeService` (bot → perfil do bot),
   `CreatePixTransactionService` (API legada sem bot → default) e a cobrança
   do portal (`MerchantChargeController`, header validado) resolvem `profileId` e
   chamam `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` —
   override do perfil vence risco (Spec 122). A cobrança grava `profile_id` +
   `provider` (V58).

**Módulos não implementados ainda** (redirectores/filtro de tráfego — Specs 109/110;
ranking/premiações — 113/114; checkout/biolink/sorteio — 115/116; automações/
remarketing — 117/118; afiliado — 111/112) **não são escopados nesta fase**
(não existem tabelas no banco). Quando implementados, **adotam o mesmo padrão**
`X-Profile-ID` + escopo por bot-set/perfil (nota no design, D9).

**Semântica do filtro (segurança — não vaza dados entre perfis/lojistas):**

- Header `X-Profile-ID` **presente e válido** (perfil não-deletado do lojista
  autenticado) → todas as queries escopam para aquele perfil.
- Header presente mas **inválido** (perfil de outro lojista/inexistente) → o
  escopo `profile_id = X AND merchant_id = merchant` retorna **vazio** — nunca
  cai para o lojista inteiro (sem vazamento).
- Header **ausente** (clientes legados / API sem perfil) → comportamento atual
  (lojista inteiro) — **regressão preservada**.

## Contexto (por que agora)

A fase 1 (Specs 122/123) entregou a fundação: entidade `merchant_profiles`,
frota de bots por perfil (`store_bots.profile_id`), seletor no HeaderGlobal,
header `X-Profile-ID` e override por perfil no `RoutingPolicyResolver`. O que
falta para a visão do usuário ("1 conta que se comporta como N empresas"): o
**switch de perfil precisa escopar os dados de verdade** — hoje todas as queries
do portal filtram só por `merchantId` (`findByMerchantId`/`findByIdAndMerchantId`
nos serviços: `MerchantDashboardService`, `MerchantSalesService`,
`CrmLeadService`, `StoreBotManagementUseCase`, `BotMetricsService`,
`BroadcastQueryService`, `TrackingProfileQueryService`). Análise do código real
(2026-08-15): `pix_transactions.bot_id` existe (V50, nullable) — base para
derivar perfil na maioria dos fluxos; o fluxo legado `/transactions` cria sem
bot (bot_id null) — por isso `pix_transactions.profile_id` é necessária para não
perder transações avulsas/API no filtro.

## Critérios de aceite

1. **Migration V70** — `pix_transactions.profile_id UUID NULL REFERENCES
   merchant_profiles(id)` + índice `ix_pix_transactions_profile`; **backfill**:
   transações com `bot_id` → perfil do bot; transações sem `bot_id` → perfil
   default do lojista; transações de lojista sem perfil (dado legado anterior à
   V69) → NULL (não-escopadas, visíveis só sem header).
2. **Resolução de perfil nas cobranças** — `CreatePixChargeService`: `bot_id`
   presente → perfil do bot; ausente → perfil default do lojista. `MerchantChargeController`: perfil do header validado. `CreatePixTransactionService` (API
   legada): perfil default. Todos chamam `RoutingPolicyResolver.resolveProviders(
   merchantId, profileId)` e gravam `pix_transactions.profile_id` na criação.
3. **Filtro por perfil no portal** — header válido → todos os endpoints do
   portal escopam: dashboard/sales por `profile_id`; CRM/broadcasts/tracking/
   métricas por bot-set (`store_bots.profile_id = ativo`); bots listam só a
   frota do perfil. Header inválido → **vazio** (nunca lojista inteiro). Header
   ausente → lojista inteiro (regressão).
4. **Rota viva** — cobrança criada num perfil com `gateway_route = "pagarme"` →
   `pix_transactions.provider = pagarme` (via router); sem perfil → política por
   risco atual (regressão Spec 91); fallback/circuit breaker intactos.
5. **Não vazamento entre perfis** — teste de segurança: header com perfil do
   mesmo lojista → só dados do perfil; header com perfil de **outro** lojista →
   vazio; sem header → lojista inteiro; trocar perfil no meio da sessão → a
   próxima query já escopa o novo perfil.
6. **Módulos escopados** — dashboard (KPIs/gráficos/atenção), vendas (extrato/
   métricas/detalhe), CRM (leads/summary/stream), bots (frota + métricas),
   broadcasts (lista/detalhe), tracking (perfis/eventos) refletem o perfil ativo.
7. **Testes** — `mvn test` verde (migração/backfill; resolver de escopo;
   filtros por módulo; segurança; regressão sem header) + E2E
   (`profile-scope.spec.js`): 2 perfis com bots/transações distintos → trocar
   perfil → dashboard/CRM/extrato/bots/broadcasts mostram só o perfil; header
   inválido → vazio; sem header → tudo.

## Dependências

- **Spec 122** (backend fase 1) — `merchant_profiles`, `store_bots.profile_id`,
  `RoutingPolicyResolver.resolveProviders(merchantId, profileId)`, semântica do
  header `X-Profile-ID`.
- **Spec 123** (UI fase 1) — `apiClient` já envia `X-Profile-ID` (todas as
  chamadas autenticadas) — a UI não muda para o filtro entrar em ação.
- **Spec 91** (router) — `PixProviderRouter`/`RoutingPolicyResolver` (override
  por perfil; regressão).
- **Specs 74/75/79/81/83** — `store_bots`, `bot_leads`/funil, `broadcasts`,
  tracking, CRM (tabelas/âmbito do bot-set).
- **Specs 109–119** (futuros) — quando implementados, adotam o padrão de escopo
  (D9).

## Fora de escopo

- **Backend/UI da fase 1** — Specs 122/123 (já `verified`; ordem: 122 → 123 →
  esta).
- **Módulos ainda não implementados** (redirectores, ranking, checkout/biolink,
  automações/remarketing, afiliado) — o padrão fica documentado (D9), a
  implementação do escopo acompanha cada spec futura.
- **Payout por perfil** — `PayoutProviderRouter` (Spec 92) inalterado (decisão:
  só cobrança).
- **Header como fonte de decisão de rota** — nunca; o header só escopa dados
  (rota vem do perfil no banco).
- **Wallet/saldo** — o saldo é global do lojista (não é escopado por perfil);
  apenas o extrato (transações) é escopado.
