---
name: Dashboard rico — UI do portal
status: verified
references:
  - "management/vision.md#V78"
  - "management/specs/103-dashboard-rico-backend/requirements.md"
  - "management/specs/21-merchant-dashboard/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/113-ranking-premiacoes-backend/requirements.md"
---

# Spec 104 — Dashboard rico: UI do portal

## O que entrega

Reescrita da `DashboardPage.tsx` do portal (Spec 21/46) com o dashboard da
descrição de produto do usuário (2026-08-15). Puramente apresentação sobre os
endpoints da Spec 103 (e reuso da Spec 21). Padrão UX da Spec 73.

1. **Cards de KPIs**: Vendas Aprovadas (volume + count), Taxa de Conversão,
   Total Starts, Ticket Médio — do `rich-summary`.
2. **Gráfico de Desempenho Temporal** ("Seu Desempenho"): curva das vendas ao
   longo dos dias da semana — do `performance-series` (SVG/CSS puro, sem lib —
   mesmo padrão do console).
3. **Log de Atividades em Tempo Real**: feed cronológico (eventos de início de
   conversa e pagamento aprovado) — do `activity-log`, com **polling curto**
   (ex.: 10s) para atualização instantânea.
4. **Seção Premiações/Metas**: progresso visual para desbloqueio de recompensas
   — dados da Spec 113 quando implementada; placeholder "em breve" até lá.
5. **Top 5 Players**: tabela lateral dos maiores faturadores da plataforma em
   tempo real — do `top-players`.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — dashboard principal do portal de
referência. O `DashboardPage` atual tem 4 cards básicos + lista de vendas;
falta o rico.

## Critérios de aceite

1. **Cards de KPI** (4): Vendas Aprovadas (`approvedVolume` + `approvedCount`),
   Taxa de Conversão (`conversionRate`%), Total Starts (`totalStarts`),
   Ticket Médio (`averageTicket`) — `formatMoney`/`formatPercent` (Spec 73).
2. **Gráfico temporal**: 7 barras/curva por dia da semana (Seg–Dom) com
   `value`; tooltip com o valor; zero-fill. Acessível (SVG com `aria-label` +
   `<title>` por barra).
3. **Log em tempo real**: lista vertical com ícone por tipo (lead/conversa/
   venda/estorno), mensagem amigável e hora relativa; **polling 10s** (sem
   SSE — KISS; o CRM já usa SSE para chat, o log é polling curto); auto-scroll
   para o novo evento.
4. **Top 5 Players**: cards laterais `{rank, merchantName, approvedVolume}` —
   destaque para o próprio lojista (quando presente); empty state.
5. **Premiações/Metas**: seção com placeholder "Em breve" **até** a Spec 113
   ser implementada; depois consome `GET /portal/rewards` (dependência de
   implementação, não de contrato).
6. **Loading/erro**: `Spinner` padrão, `ErrorBanner`, empty states (Spec 73).
7. **Sem regressão**: os dados da Spec 21 (série/recent-sales/attention-points)
   podem ser mantidos em seções secundárias (ou substituídos — decisão: manter
   recent-sales como "Últimas vendas" e os pontos de atenção como alertas).
8. **Testes**: `npm run build` (tsc) limpo no portal + E2E Playwright
   (`portal-dashboard-rich-ui.spec.js`): cards corretos, gráfico com 7 pontos,
   log com evento, top 5; suíte completa sem regressão.

## Dependências

- **Spec 103** (backend) — endpoints ricos.
- **Spec 21** — dados existentes mantidos.
- **Spec 73** — UX.
- **Spec 113** (ranking/premiações) — dados de premiação (placeholder até lá).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — a UI consome dados
  já **escopados pelo perfil ativo** (Spec 124); o dashboard deve refletir a
  lente Bot/Gateway do `ProfileSelector` (perfis `BOT_ONLY`/`GATEWAY_ONLY`,
  V76) sem quebrar o seletor 2 níveis no shell.

## Fora de escopo

- **Backend** — Spec 103.
- **Página completa de Ranking** — Spec 114 (aqui só o Top 5 lateral).
- **Página de Premiações** — Spec 114 (aqui só a seção de progresso).
