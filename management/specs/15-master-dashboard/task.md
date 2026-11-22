# Spec 15 — Task breakdown (Dashboard master)

## Domínio
- [x] `MasterEarnings` / `MasterFees` / `MasterTransactionSearchQuery`
      (`domain/model/master/`)
- [x] `MasterDashboardUseCase` (port-in) + `MasterDashboardPort` (port-out)

## Aplicação
- [x] `MasterDashboardService` (transactions delega; earnings/fees validam 404)

## Infraestrutura
- [x] `MasterDashboardPersistenceAdapter` (Specifications + SUM/COALESCE)
- [x] `PixTransactionJpaRepository.sumAmountByMerchantIdAndStatus` +
      `JpaSpecificationExecutor`
- [x] `WithdrawalJpaRepository.sumCompletedAmountByMerchantId`
- [x] `MasterDashboardController` (transactions / earnings / fees + parsing)
- [x] DTOs: `MasterEarningsResponse`, `MasterFeesResponse`

## Testes e verificação
- [x] `MasterDashboardServiceTest` (5 testes)
- [x] `mvn test` verde (**100 testes**)
- [x] Validação manual curl: earnings (wallet/approved/withdrawn), fees com e
      sem fatura, mês inválido → 400, transações paginadas
- [x] Docs: task.md, design.md; roadmap `implemented`; README; commit

## Fora de escopo
- [ ] Escrita no domínio financeiro (exceto controles de saque da Spec 14)
- [ ] Gráficos/exportação (só dados JSON nesta spec)
