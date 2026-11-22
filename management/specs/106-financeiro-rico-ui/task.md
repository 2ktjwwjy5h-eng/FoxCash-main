---
name: Financeiro rico — UI do portal
status: verified
---

# Spec 106 — Task: Financeiro rico (UI)

## F0 — Página + componentes

- [ ] **T0.1** — `FinancialPage.tsx` (rota `/financial`) — layout (D3), estado
      de `period`.
- [ ] **T0.2** — `PeriodTabs.tsx`, `PixSummaryCards.tsx`, `MethodChart.tsx`,
      `FinancialTable.tsx`, `PeriodSummary.tsx` — consumo da Spec 105.

## F1 — Rotas/SPA + build

- [ ] **T1.1** — `/financial` no `PortalSpaController` + `spaPublicPaths()`
      (lição Specs 80/84) + menu (Spec 102 já tem placeholder).
- [ ] **T1.2** — `npm run build` (tsc) limpo no portal.

## F2 — E2E + fechamento

- [ ] **T2.1** — E2E `portal-financial-ui.spec.js` (D4).
- [ ] **T2.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 106`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `useAsync`, componentes Spec 73) — sem achados.
