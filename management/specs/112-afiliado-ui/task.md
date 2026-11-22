---
name: Afiliado/Comissões — UI do portal
status: verified
---

# Spec 112 — Task: Afiliado/Comissões (UI)

## F0 — Página + resumo

- [ ] **T0.1** — `AffiliatePage.tsx` (rota `/affiliate`) + `AffiliateSummary.tsx`
      (Spec 111).
- [ ] **T0.2** — `AffiliatesTable.tsx` + `AffiliateModal.tsx` (criar/editar;
      resolver lojista por e-mail — `GET /affiliates/resolve?q=` aditivo).
- [ ] **T0.3** — `CommissionHistory.tsx` (por coprodutor selecionado).

## F1 — Rotas/SPA + build

- [ ] **T1.1** — `/affiliate` no `PortalSpaController` + `spaPublicPaths()`.
- [ ] **T1.2** — `npm run build` (tsc) limpo no portal.

## F2 — E2E + fechamento

- [ ] **T2.1** — E2E `portal-affiliate-ui.spec.js` (D3).
- [ ] **T2.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 112`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `ConfirmDialog`) — sem achados.
