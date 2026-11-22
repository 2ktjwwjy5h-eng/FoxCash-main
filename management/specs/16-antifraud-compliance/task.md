# Spec 16 — Task breakdown (Antifraude e compliance)

## Domínio
- [x] `MerchantRiskSnapshot` + `RiskSearchQuery` (`domain/model/risk/`)
- [x] Portas: `RiskMonitoringUseCase`, `QueryRiskUseCase`,
      `MerchantStatusControlUseCase`, `RiskRepository`
- [x] `TransactionRepository`: `countByMerchantId`,
      `countByMerchantIdAndStatus`, `sumAmountByMerchantIdAndStatus`
- [x] `MerchantRepository.findAll()`

## Aplicação
- [x] `RiskMonitoringService` (job: indicadores + flags por lojista)
- [x] `MerchantStatusControlService` (suspend/release)
- [x] `QueryRiskService` (search + latestByMerchant)

## Infraestrutura
- [x] Migration `V9__merchant_risk.sql`
- [x] `MerchantRiskJpaEntity` + `MerchantRiskMapper` + `MerchantRiskJpaRepository` +
      `MerchantRiskPersistenceAdapter`
- [x] `RiskMonitoringScheduler` (ShedLock) + `RiskProperties` + config yml
- [x] `MasterRiskController` (GET /risk, GET /merchants/{id}/risk,
      POST suspend/release)
- [x] DTO `MerchantRiskResponse` + `RiskSnapshotNotFoundException` + handler 404

## Testes e verificação
- [x] `RiskMonitoringServiceTest` (6 testes)
- [x] `MerchantStatusControlServiceTest` (3 testes)
- [x] `QueryRiskServiceTest` (2 testes)
- [x] `mvn test` verde (**111 testes**)
- [x] Validação manual: job gravou snapshots, GET /master/risk (200/400),
      snapshot 404, suspend/release (204 → 401 → 200), 404/401/403
- [x] Docs: requirements (verified), design, task; roadmap `implemented`; commit

## Fora de escopo
- [ ] Alimentação de chargeback/denúncias (disputa fora do fluxo Pix)
- [ ] Suspensão automática (decisão é do master — ADR-008)
- [ ] Reserva de valor / bloqueio permanente (§7)
