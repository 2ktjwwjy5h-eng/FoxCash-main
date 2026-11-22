---
name: Dashboard rico — KPIs estendidos, gráfico temporal, log em tempo real e Top 5 (backend)
status: verified
references:
  - "management/vision.md#V78"
  - "management/specs/21-merchant-dashboard/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/30-wallet-reconciliation/requirements.md"
---

# Spec 103 — Dashboard rico: KPIs estendidos, gráfico temporal, log e Top 5 (backend)

## O que entrega

Backend **aditivo read-only** que enriquece o dashboard do portal (Spec 21 —
hoje só summary/série/recent-sales/attention-points) com os KPIs e feeds da
descrição de produto do usuário (2026-08-15).

1. **KPIs estendidos** (`GET /api/v1/portal/dashboard/summary` ganha campos
   aditivos — ou endpoint novo `/portal/dashboard/rich-summary`):
   - **Vendas Aprovadas**: `approvedVolume` (financeiro convertido) +
     `approvedCount` (nº absoluto de aprovações).
   - **Taxa de Conversão**: `conversionRate` (% entre inícios e Pix pagos —
     já existe; mantém).
   - **Total Starts**: `totalStarts` (clientes que iniciaram checkout ou
     conversa — `bot_funnel_events` Spec 75: `checkout_started` + leads).
   - **Ticket Médio**: `averageTicket` (valor médio por comprador — Σ APPROVED
     ÷ compradores únicos ou ÷ vendas).
2. **Gráfico de desempenho temporal** (`GET /api/v1/portal/dashboard/
   performance-series`): curva "Seu Desempenho" mapeando vendas ao longo dos
   **dias da semana** (seg–dom, reuso `/revenue-series` Spec 21 agregado por
   dia da semana da janela).
3. **Log de atividades em tempo real** (`GET /api/v1/portal/dashboard/
   activity-log`): feed cronológico com eventos recentes (início de conversa →
   `bot_funnel_events`/`bot_leads` Spec 75; pagamento aprovado →
   `pix_transactions` APPROVED; reembolso/chargeback) — polling curto do
   frontend.
4. **Top 5 Players** (`GET /api/v1/portal/dashboard/top-players`): os maiores
   faturadores da **plataforma** (visível ao lojista — ranking global por
   faturamento, mesmo critério da Spec 113; aqui é o subset Top 5).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — dashboard principal do portal de
referência: cards de KPI, gráfico, log em tempo real, premiações e Top 5
Players. Lacuna: o `DashboardPage` atual tem só os 4 cards básicos (Spec 21).

## Critérios de aceite

1. **KPIs**: `rich-summary` (ou summary estendido) devolve `{approvedVolume,
   approvedCount, conversionRate, totalStarts, averageTicket, ...}` — janela
   padrão mês corrente (padrão Spec 21). `totalStarts` = COUNT `checkout_started`
   (Spec 75) + COUNT `bot_leads` novos (Spec 83) na janela (deduplicado —
   decisão na implementação, documentada). `averageTicket` = `approvedVolume ÷
   approvedCount` (0 sem vendas).
2. **Performance-series**: pontos `{dayOfWeek (0-6), value}` para a janela —
   agregado das vendas aprovadas por dia da semana; dias sem venda = 0
   (zero-fill, padrão Spec 22).
3. **Activity-log**: `{eventId, type (LEAD_CREATED|CHECKOUT_STARTED|SALE_PAID|
   SALE_REFUNDED|CHARGEBACK), message, createdAt}` — últimos 20, desc. Sem PII
   (nome do lead mascarado; usa `buyerIdHash` para pagamentos).
4. **Top-players**: `{rank, merchantName, approvedVolume}` Top 5 global da
   plataforma na janela (mês corrente), com o nome do lojista (não o e-mail) e
   volume. 401/403 para quem não é portal.
5. **Sem PII em logs** (padrão `SensitiveDataLoggingFilter`).
6. **Segurança/rate limit**: `/api/v1/portal/**` já coberto (Spec 58).
7. **Contratos existentes inalterados**: `/dashboard/summary` etc. mantidos;
   os novos dados entram em **endpoints aditivos** (`/dashboard/rich-summary`,
   `/dashboard/performance-series`, `/dashboard/activity-log`,
   `/dashboard/top-players`).
8. **Testes**: unitários + integração + E2E; `mvn test` verde + E2E completo.

## Dependências

- **Spec 21** (dashboard) — base summary/série.
- **Spec 75** (funil) — `bot_funnel_events`/`bot_leads` para Starts e log.
- **Spec 83** (CRM) — leads recentes no log.
- **Spec 30** (reconciliação) — somas APPROVED agregadas (M8).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — o dashboard é
  **escopado pelo perfil ativo** (Spec 124: `pix_transactions.profile_id`,
  V70); KPIs/série/log devem respeitar o `X-Profile-ID` e a lente Bot/Gateway —
  um perfil `BOT_ONLY` não deve somar vendas de gateway e vice-versa.

## Fora de escopo

- **UI do dashboard** — Spec 104.
- **Ranking completo/paginação** — Spec 113 (aqui só Top 5 do dashboard).
- **Premiações no dashboard** — Spec 113 (aqui só dados; a seção visual na 104
  usa os dados da 113 quando implementada).
