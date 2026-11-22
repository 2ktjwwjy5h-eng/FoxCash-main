---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (UI)
status: verified
---

# Spec 118 — Task: Automações avançadas (UI)

## F0 — FlowsPage

- [ ] **T0.1** — `FlowsPage.tsx` (rota `/flows`) + `FlowNodeEditor.tsx` (nós
      sequenciais, reorder ↑/↓, validação por tipo).
- [ ] **T0.2** — CRUD via Spec 117; ativar/desativar.

## F1 — Redirects + Remarketing

- [ ] **T1.1** — `RedirectsPage.tsx` (rota `/redirects`) — reuso da Spec 110
      (filtro filtro de tráfego).
- [ ] **T1.2** — `RemarketingPage.tsx` (rota `/remarketing`) — CRUD campanhas +
      logs.

## F2 — Rotas/SPA + build

- [ ] **T2.1** — `/flows`, `/redirects`, `/remarketing` no
      `PortalSpaController` + `spaPublicPaths()`.
- [ ] **T2.2** — `npm run build` (tsc) limpo no portal.

## F3 — E2E + fechamento

- [ ] **T3.1** — E2E `portal-automacoes-ui.spec.js` (D5).
- [ ] **T3.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 118`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `LinksTable` Spec 110) — sem achados.
