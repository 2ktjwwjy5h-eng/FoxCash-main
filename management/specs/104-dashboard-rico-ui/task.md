---
name: Dashboard rico — UI do portal
status: verified
---

# Spec 104 — Task: Dashboard rico (UI)

## F0 — KPIs + gráfico

- [ ] **T0.1** — `KpiCard.tsx`; `DashboardPage.tsx` reescrita com 4 cards
      (Vendas Aprovadas/Conversão/Total Starts/Ticket Médio) via
      `GET /portal/dashboard/rich-summary` (Spec 103).
- [ ] **T0.2** — `PerformanceChart.tsx` (SVG 7 barras Seg–Dom) via
      `GET /portal/dashboard/performance-series`.

## F1 — Log em tempo real + Top 5 + Premiações

- [ ] **T1.1** — `ActivityLog.tsx` (polling 10s, auto-scroll) via
      `GET /portal/dashboard/activity-log`.
- [ ] **T1.2** — `TopPlayers.tsx` via `GET /portal/dashboard/top-players`
      (destaque para o próprio lojista).
- [ ] **T1.3** — `RewardsProgress.tsx` placeholder → dados da Spec 113 quando
      implementada.

## F2 — Build + E2E

- [ ] **T2.1** — `npm run build` (tsc) limpo no portal.
- [ ] **T2.2** — E2E `portal-dashboard-rich-ui.spec.js` (D4).
- [ ] **T2.3** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 104`)

- [ ] Checar âncoras (`DashboardPage`, `useAsync`, `formatMoney`, E2E) —
      sem achados.
