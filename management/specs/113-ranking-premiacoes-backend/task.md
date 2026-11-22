---
name: Ranking + Premiações — concurso de faturamento e metas com recompensas (backend)
status: verified
---

# Spec 113 — Task: Ranking + Premiações (backend)

## F0 — Migration + config

- [ ] **T0.1** — Migration **V64** (`reward_progress`, D2).
- [ ] **T0.2** — `RewardsProperties` (`tiers`, `enabled`, `cron`) + binding
      contra o yml real (padrão Spec 68/L16).

## F1 — Services

- [ ] **T1.1** — `RankingService`: `ranking(merchantId, period)` — posição +
      top10 (Σ APPROVED, PeriodFilter Spec 105).
- [ ] **T1.2** — `RewardsService`: `rewards(merchantId)` — progresso + unlock
      on-the-fly (D4.2).
- [ ] **T1.3** — `RewardsScheduler` (ShedLock + `JobExecutionService`, cron
      `foxcash.rewards.cron`, kill-switch) — recalcula, marca `unlocked` +
      `REWARD_UNLOCKED` no outbox (Spec 35).

## F2 — Controller

- [ ] **T2.1** — `PortalRankingController` (`/api/v1/portal/ranking`,
      `/api/v1/portal/rewards`), ROLE_MERCHANT_PORTAL.
- [ ] **T2.2** — `PlatformAction` += `REWARD_UNLOCKED`; métricas
      `nyx_rewards_*`.

## F3 — Testes + E2E

- [ ] **T3.1** — Unitários/integração (D6) + `mvn test` verde.
- [ ] **T3.2** — E2E `portal-ranking-rewards.spec.js` + roadmap/status.

## Verificação (`/spec-verify 113`)

- [ ] Checar âncoras (`TransactionRepository`, `JobExecutionService`,
      `ShedLock`, outbox Spec 35, `Money.normalize`) — sem achados.
