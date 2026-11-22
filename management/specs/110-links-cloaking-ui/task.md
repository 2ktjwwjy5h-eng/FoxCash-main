---
name: Gestão de Links e Campanhas — UI do portal
status: verified
---

# Spec 110 — Task: Gestão de Links (UI)

## F0 — Página + tabela

- [ ] **T0.1** — `LinksPage.tsx` (rota `/links`) + `LinksTable.tsx` (Spec 109):
      KPIs, filtros, colunas, ações (copiar/pausar/deletar).
- [ ] **T0.2** — `PrepareCampaignModal.tsx` (UTMs + filtro de tráfego) →
      `POST /links/prepare-campaign`.

## F1 — Modais de gestão

- [ ] **T1.1** — `CloakerSettingsModal.tsx` (toggle + intermediate) →
      `PATCH /links/{id}`.
- [ ] **T1.2** — `DomainsManager.tsx` + `SaleCodesManager.tsx` (CRUD).

## F2 — Rotas/SPA + build

- [ ] **T2.1** — `/links` no `PortalSpaController` + `spaPublicPaths()`.
- [ ] **T2.2** — `npm run build` (tsc) limpo no portal.

## F3 — E2E + fechamento

- [ ] **T3.1** — E2E `portal-links-ui.spec.js` (D4).
- [ ] **T3.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 110`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `copyToClipboard`, `ConfirmDialog`) — sem achados.
