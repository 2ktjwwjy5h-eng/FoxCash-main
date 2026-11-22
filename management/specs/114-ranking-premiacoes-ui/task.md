---
name: Ranking + Premiações — UI do portal
status: verified
---

# Spec 114 — Task: Ranking + Premiações (UI)

## F0 — Páginas

- [ ] **T0.1** — `RankingPage.tsx` (rota `/ranking`) + `RankingPodium.tsx`
      (Spec 113).
- [ ] **T0.2** — `RewardsPage.tsx` (rota `/rewards`) + `RewardCard.tsx`.

## F1 — Rotas/SPA + build

- [ ] **T1.1** — `/ranking`, `/rewards` no `PortalSpaController` +
      `spaPublicPaths()`.
- [ ] **T1.2** — `npm run build` (tsc) limpo no portal.

## F2 — E2E + fechamento

- [ ] **T2.1** — E2E `portal-ranking-rewards-ui.spec.js` (D4).
- [ ] **T2.2** — Suíte completa sem regressão + roadmap/status.

## Verificação (`/spec-verify 114`)

- [ ] Checar âncoras (`App.tsx`, `PortalSpaController`, `spaPublicPaths`) —
      sem achados.
