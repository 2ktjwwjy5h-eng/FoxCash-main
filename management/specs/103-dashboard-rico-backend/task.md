---
name: Dashboard rico — KPIs estendidos, gráfico temporal, log em tempo real e Top 5 (backend)
status: verified
---

# Spec 103 — Task: Dashboard rico (backend)

## F0 — Service + agregados

- [ ] **T0.1** — `RichDashboardService`: `richSummary(merchantId, window)`
      (approvedVolume/count, conversionRate, totalStarts, averageTicket);
      `performanceSeries(merchantId, window)` (7 pontos zero-fill por DOW);
      `activityLog(merchantId, limit)` (20, desc);
      `topPlayers(window)` (Top 5 global).
- [ ] **T0.2** — Repositórios: `TransactionRepository` += `sumApprovedByDow`,
      `countDistinctBuyers` (se ticket médio for por comprador); reuso
      `BotFunnelEventRepository`/`BotLeadRepository`; query global de top players
      (GROUP BY merchant + SUM APPROVED).
- [ ] **T0.3** — DTOs: `RichSummaryResponse`, `PerformancePoint`,
      `ActivityEvent`, `TopPlayer` — records.

## F1 — Controller

- [ ] **T1.1** — `PortalRichDashboardController` (`/api/v1/portal/dashboard/*`):
      `/rich-summary`, `/performance-series`, `/activity-log`, `/top-players`.
      `QueryParamParser` para janela/limit (cap 50).
- [ ] **T1.2** — Contratos existentes inalterados (summary/série/recent-sales/
      attention-points intactos).

## F2 — Testes

- [ ] **T2.1** — `RichDashboardServiceTest` (D4) + `PortalRichDashboardControllerTest`
      (200/400/401/403).
- [ ] **T2.2** — `mvn test` verde.

## F3 — E2E + fechamento

- [ ] **T3.1** — E2E `portal-dashboard-rich.spec.js` (D4 — seeds de 2 lojistas
      para Top 5).
- [ ] **T3.2** — Atualizar roadmap/status.

## Verificação (`/spec-verify 103`)

- [ ] Checar âncoras (`MerchantDashboardService`, `TransactionRepository`,
      `BotFunnelEventRepository`, `BotLeadRepository`, `QueryParamParser`,
      `RateLimitKey` portal) — sem achados de escopo.
