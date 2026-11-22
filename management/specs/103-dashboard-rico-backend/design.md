---
name: Dashboard rico — KPIs estendidos, gráfico temporal, log em tempo real e Top 5 (backend)
status: verified
---

# Spec 103 — Design: Dashboard rico (backend)

## D1 — Conceito

Um **`RichDashboardService`** (aplicação) com endpoints aditivos que **compõem
dados existentes** (`pix_transactions`, `bot_funnel_events`, `bot_leads`),
padrão dos agregados SQL (M8/Spec 30). O summary atual (Spec 21) fica intacto.

```text
PortalRichDashboardController (REST, ROLE_MERCHANT_PORTAL)
   └─ RichDashboardService (aplicacao)
        ├─ TransactionRepository (Spec 25/30) — SUM/COUNT APPROVED, por dia da semana
        ├─ BotFunnelEventRepository (Spec 75) — checkout_started/leads
        └─ (Top players) query global de faturamento por lojista
```

## D2 — Endpoints

### D2.1 `GET /api/v1/portal/dashboard/rich-summary`

`{approvedVolume, approvedCount, conversionRate, totalStarts, averageTicket}`
(aditivo; janela = mês corrente UTC, padrão Spec 21). `totalStarts` =
COUNT `checkout_started` + COUNT `bot_leads` novos (janela) — deduplicar por
`platform_user_id`/`bot_leads.id` (decisão na implementação; documentada no
task). `averageTicket` = `approvedVolume / approvedCount` (0 sem vendas).

### D2.2 `GET /api/v1/portal/dashboard/performance-series`

`[{dayOfWeek, label (Seg/Dom), value}]` — 7 pontos zero-fill por dia da semana
da janela (agregação `EXTRACT(DOW FROM created_at)` no Postgres + GROUP BY).

### D2.3 `GET /api/v1/portal/dashboard/activity-log`

`[{eventId, type, message, createdAt}]` — últimos 20, desc. Fontes:
`bot_leads` (LEAD_CREATED), `bot_funnel_events` (CHECKOUT_STARTED),
`pix_transactions` APPROVED/REFUNDED (SALE_PAID/SALE_REFUNDED) e
`chargebacks` (CHARGEBACK). Mensagem amigável ("Venda aprovada: R$ 4,89";
"Novo lead: João S." — nome mascarado se PII).

### D2.4 `GET /api/v1/portal/dashboard/top-players`

`[{rank, merchantName, approvedVolume}]` — Top 5 da plataforma por faturamento
APROVADO na janela (mês). Nome do lojista (nunca e-mail). Zero PII.

## D3 — Persistência

Nenhuma migration (dados existentes). Índices existentes cobrem os agregados
(Specs 25/44/46). Se o E2E mostrar lentidão no Top-players, índice aditivo
documentado (evolução).

## D4 — Testes

**Unitários:** `RichDashboardServiceTest` (KPIs, totalStarts com/sem leads,
averageTicket 0, performance-series zero-fill 7 pontos, activity-log ordenado/
vazio, top-players ordem e limite). **Integração:** controller 200/401/403.
**E2E:** `portal-dashboard-rich.spec.js` — summary estendido, série, log,
top 5 (com seeds de 2 lojistas).

## D5 — Não ignorar a Spec 139 (escopo por perfil)

KPIs/série/log são **escopados pelo perfil ativo** (Spec 124 —
`pix_transactions.profile_id`, V70): usar `ProfileScopeResolver` e respeitar o
`X-Profile-ID`; um perfil `BOT_ONLY` não deve somar vendas de gateway e
vice-versa. Lente Bot/Gateway (Spec 139) apenas seleciona o contexto; o escopo
de dados é do backend.
