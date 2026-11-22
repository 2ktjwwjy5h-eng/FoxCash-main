---
name: Dashboard rico — UI do portal
status: verified
---

# Spec 104 — Design: Dashboard rico (UI)

## D1 — Conceito

Reescrita da `DashboardPage.tsx` em **seções** (grid): KPIs no topo, gráfico +
Top 5 lado a lado, log em tempo real e premiações. Consumo da Spec 103 com
`useAsync` + polling 10s no log. CSS puro (Spec 73).

```text
portal/src/pages/DashboardPage.tsx   → reescrita (seções)
portal/src/components/KpiCard.tsx    → card de KPI (título/valor/sub)
portal/src/components/PerformanceChart.tsx → SVG de barras por dia da semana
portal/src/components/ActivityLog.tsx → feed com polling
portal/src/components/TopPlayers.tsx → lista lateral
portal/src/components/RewardsProgress.tsx → seção de premiações (Spec 113)
```

## D2 — Layout

Grid principal: `[KPIs 4 cards] / [gráfico (2/3) | Top 5 (1/3)] / [log |
premiações]` — responsivo (empilha em telas estreitas).

## D3 — Componentes

- **`KpiCard`**: `{title, value, sub}` — valor formatado (`formatMoney`/
  `formatPercent`), sub texto (ex.: "N aprovações").
- **`PerformanceChart`**: SVG inline — 7 `<rect>` (barras) com altura
  proporcional ao `value`; `aria-label` + `<title>` por barra; label Seg–Dom.
- **`ActivityLog`**: lista `<li>` com ícone por `type` (emoji/SVG), mensagem e
  hora relativa (`Intl.RelativeTimeFormat`); **polling 10s** com `setInterval`
  limpo no unmount; auto-scroll.
- **`TopPlayers`**: lista `{rank}. {merchantName} — R$ {approvedVolume}`;
  destaque para o próprio lojista (comparação por id/`me.id`).
- **`RewardsProgress`**: placeholder "Em breve" — trocado pelos dados da Spec
  113 quando implementada (contrato: `GET /portal/rewards`).

## D4 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-dashboard-rich-ui.spec.js`: login → 4 KPIs corretos → gráfico com
  7 barras → log com 1 evento (seed de venda) → Top 5 com 2 lojistas → empty
  states. Suíte completa sem regressão.

## D5 — Wireframe do dashboard rico (registro de design 2026-08-16)

Grid em seções (D2), aprovado no detalhamento do Gap 4.

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Dashboard                                                     [período]│
│                                                                         │
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│ │ Vendas Aprov.│ │ Taxa Conv.   │ │ Total Starts │ │ Ticket Médio │    │
│ │   R$ 12.450  │ │   18,5%      │ │    1.234     │ │   R$ 89,90   │    │
│ │  42 aprovadas│ │              │ │              │ │              │    │
│ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘    │
│                                                                         │
│ ┌───────────────────────────────────┐  ┌────────────────────────────┐  │
│ │ Desempenho (por dia da semana)    │  │   TOP 5 PLAYERS           │  │
│ │   ██  ███  ██  ████  ██  ██  █  │  │  1º Loja A   R$ 9.999      │  │
│ │  Seg Ter Qua Qui Sex Sáb Dom     │  │  2º Loja B   R$ 8.500      │  │
│ │   (SVG/CSS puro, 7 barras)       │  │  ...                       │  │
│ │                                   │  │  Você está em 2º         │  │
│ └───────────────────────────────────┘  └────────────────────────────┘  │
│                                                                         │
│ ┌───────────────────────────────────┐  ┌────────────────────────────┐  │
│ │  Atividade em tempo real        │  │   PREMIAÇÕES / METAS      │  │
│ │  (polling 10s)                    │  │  Meta: R$ 50.000/mês       │  │
│ │  • 14:32 Venda aprovada R$ 120   │  │  ████████░░░░ 72%          │  │
│ │  • 14:28 Lead novo — Telegram    │  │  Próxima: "Shark Blood"    │  │
│ │  • 14:25 Checkout iniciado       │  │   Conquistas desbloqueadas│  │
│ │  ...                              │  │                            │  │
│ └───────────────────────────────────┘  └────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

Notas de implementação:
- **KPIs** (Spec 103): Vendas Aprovadas (volume + count), Taxa de Conversão,
  Total Starts, Ticket Médio — componentes `KpiCard` (D3).
- **Gráfico** = `PerformanceChart` SVG inline (7 barras, dia da semana).
- **Log** = `ActivityLog` com polling 10s (Spec 103 `activity-log`), ícone por
  `type`, hora relativa, auto-scroll.
- **Top 5** = `TopPlayers` (Spec 103 `top-players`), destaque do próprio lojista.
- **Premiações** = `RewardsProgress` placeholder "Em breve" até a Spec 113.
