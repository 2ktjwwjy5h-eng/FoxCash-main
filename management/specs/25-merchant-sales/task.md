# Spec 25 — Task breakdown

Implementação concluída em 2026-08-04. Verificação: `mvn test` **204/0/0**
(196 + 8 novos) e E2E Playwright **10/10** (9 + cenário 10 novo).

## Implementação

- [x] `TransactionRepository` (port out): `searchSales` + `countByMerchantIdAndCreatedAtBetween`
      + `countByMerchantIdAndStatusAndCreatedAtBetween` +
      `sumAmountByMerchantIdAndStatusAndCreatedAtBetween`.
- [x] `PixTransactionJpaRepository`: derived queries de contagem + `@Query`
      `COALESCE(SUM(...), 0)` por status no intervalo.
- [x] `TransactionPersistenceAdapter`: `searchSales` via `Specification` +
      `PageRequest` (createdAt DESC) e delegações das contagens/soma.
- [x] `MerchantSalesUseCase` (port in) + records `Sale`, `Metrics`, `SaleDetail`.
- [x] `MerchantSalesService`: list (netAmount = gross − taxa do plano), metrics
      (clamp 1–90, janela via `Clock`, conversão = concluídas/criadas × 100),
      detail (404 inexistente / 403 outro lojista / payload Pix só `PENDING`).
- [x] DTOs: `PortalSaleResponse`, `PortalSalesMetricsResponse`,
      `PortalSaleDetailResponse`.
- [x] `PortalSalesController` (`/api/v1/portal/sales`): list (page/size clamp,
      parse status/from/to), metrics (`days` default 30), detail (`{id}`).

## Verificação

- [x] `MerchantSalesServiceTest` (8 testes): netAmount, passagem de filtros,
      métricas com janela e conversão, clamp de dias, zero sem transações,
      detail PENDING com payload Pix, detail APPROVED sem payload, 403 outro
      lojista, 404 inexistente.
- [x] E2E: cenário 10 (listagem filtrada, métricas, detalhe PENDING, 400 em
      parâmetro inválido, 404) + suite 9/9 sem regressão → **10/10**.
- [x] `mvn test` verde (204/0/0).
- [x] Roadmap `verified` → `implemented`; requirements/design/task atualizados
      com contagens reais (204).
- [x] Commit + push (status `implemented`).
- [ ] `/spec-audit` (pendente de execução formal do fluxo → `audited`).
