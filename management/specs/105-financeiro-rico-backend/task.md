---
name: Financeiro rico — resumo de Pix, transações com gateway e resumo do período (backend)
status: verified
---

# Spec 105 — Task: Financeiro rico (backend)

## F0 — PeriodFilter + service

- [ ] **T0.1** — `PortalPeriod` enum + `PeriodFilter` (Clock, ranges UTC:
      hoje/ontem/semana/mes/todo).
- [ ] **T0.2** — `PortalFinancialService`: `summary(merchantId, period)`;
      `transactions(merchantId, query)` (filtros period/gateway/status/flow,
      paginado, cap 100).
- [ ] **T0.3** — Reuso do helper de `customerName`/`productName`/`netAmount`
      da Spec 97 (extrair porta/helper — DRY; a Spec 97 ainda não foi
      implementada — a extração pode ser feita aqui com a mesma lógica).

## F1 — Controller + DTOs

- [ ] **T1.1** — `PortalFinancialController` (`/api/v1/portal/financial/summary`,
      `/api/v1/portal/transactions`). DTOs: `FinancialSummaryResponse`,
      `PortalFinancialTransaction`.
- [ ] **T1.2** — Contratos existentes (`/portal/wallet/*`, `/portal/sales/*`)
      inalterados.

## F2 — Testes

- [ ] **T2.1** — `PortalFinancialServiceTest` (D5) + `PortalFinancialControllerTest`
      (200/400/401/403).
- [ ] **T2.2** — `mvn test` verde.

## F3 — E2E + fechamento

- [ ] **T3.1** — E2E `portal-financial.spec.js` (D5).
- [ ] **T3.2** — Atualizar roadmap/status.

## Verificação (`/spec-verify 105`)

- [ ] Checar âncoras (`MerchantWalletService`, `MerchantSalesService`,
      `TransactionRepository`, `PricingCalculator`, `BotLeadRepository`,
      `PlanRepository`, `QueryParamParser`, `Clock`) — sem achados.
