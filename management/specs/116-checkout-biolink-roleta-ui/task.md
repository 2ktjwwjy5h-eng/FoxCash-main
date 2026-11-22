---
name: Checkout + Bio Link + Cupons & Sorteios — UI do portal
status: verified
---

# Spec 116 — Task: Checkout + Bio Link + Sorteio (UI)

## F0 — Páginas do portal

- [ ] **T0.1** — `CheckoutsPage.tsx` (rota `/checkouts`) — KPIs + lista +
      botão novo.
- [ ] **T0.2** — `CheckoutBuilderPage.tsx` (construtor + preview; toggle sorteio).
- [ ] **T0.3** — `BioLinkPage.tsx` (editor de árvore + reorder).
- [ ] **T0.4** — `SorteioPage.tsx` (prêmios dinâmicos, soma 100, testar giro).

## F1 — Páginas públicas

- [ ] **T1.1** — `public/PublicCheckout.tsx` (`/c/:slug`) — renderiza + gera Pix
      (endpoint público aditivo `POST /api/v1/public/checkout/{slug}/charge` —
      documentar na implementação).
- [ ] **T1.2** — `public/PublicBio.tsx` (`/bio/:slug`).

## F2 — Rotas/SPA + SecurityConfig

- [ ] **T2.1** — Rotas no `App.tsx` + `spaPublicPaths()` + `SecurityConfig`
      `permitAll` para `/c/**`, `/bio/**`, `/api/v1/public/**` (padrão
      storefront Spec 48).
- [ ] **T2.2** — `npm run build` (tsc) limpo no portal.

## F3 — E2E + fechamento

- [ ] **T3.1** — E2E `portal-checkout-ui.spec.js` (D6).
- [ ] **T3.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 116`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`,
      `SecurityConfig`, `ChargePage` Spec 23, `ConfirmDialog`) — sem achados.
