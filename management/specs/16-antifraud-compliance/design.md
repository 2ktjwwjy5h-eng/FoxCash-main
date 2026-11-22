# Spec 16 — Design (Antifraude e compliance: indicadores de risco + suspensão)

## 1. Domínio

### `MerchantRiskSnapshot` (entidade/registro de leitura)
`domain/model/risk/` — classe `MerchantRiskSnapshot`:
`merchantId, Instant computedAt, Money approvedVolume, long totalTransactions,
long approvedTransactions, long refundedTransactions, BigDecimal
refundRatePercent, BigDecimal chargebackRatePercent, int complaintsCount,
boolean refundAlert, boolean chargebackAlert, boolean complaintsAlert`.

### Portas
- `domain/port/in/RiskMonitoringUseCase`:
  `int computeForAllMerchants()` — calcula e grava um snapshot por lojista;
  retorna a quantidade gravada.
- `domain/port/in/QueryRiskUseCase`:
  `PageResult<MerchantRiskSnapshot> search(RiskSearchQuery)`,
  `Optional<MerchantRiskSnapshot> latestByMerchant(UUID)`.
- `domain/port/in/MerchantStatusControlUseCase`:
  `suspend(UUID)`, `release(UUID)` — escreve `SUSPENDED`/`ACTIVE`.
- `domain/port/out/RiskRepository`:
  `save`, `search(RiskSearchQuery)`, `latestByMerchant(UUID)`.
- `domain/model/risk/RiskSearchQuery`:
  `(UUID merchantId, int page, int size)`.

### Métodos novos em portas existentes
- `TransactionRepository`: `countByMerchantId(UUID)`,
  `countByMerchantIdAndStatus(UUID, TransactionStatus)`,
  `sumAmountByMerchantIdAndStatus(UUID, TransactionStatus)` (a soma já existe
  no JPA repo da Spec 15; falta expor no port).
- `MerchantRepository`: `findAll()` (todos os lojistas, não só ACTIVE — o
  monitoramento inclui suspensos).

## 2. Aplicação

### `RiskMonitoringService implements RiskMonitoringUseCase`
Para cada lojista (`merchantRepository.findAll()`):
```java
long total   = tx.countByMerchantId(id);
long refunds = tx.countByMerchantIdAndStatus(id, REFUNDED);
BigDecimal refundRate = total == 0 ? ZERO : refunds*100/total (2 casas);
boolean refundAlert = refundRate > risk.refundAlertPercent;
// chargeback/complaints: 0 nesta versão (sem fonte de dados — ADR-008)
// approvedVolume = tx.sumAmountByMerchantIdAndStatus(id, APPROVED)
```
Persiste `MerchantRiskSnapshot` (uma linha por execução — histórico).

### `MerchantStatusControlService implements MerchantStatusControlUseCase`
`suspend` → `Merchant.suspend()`; `release` → `Merchant.activate()`; persiste.

### `QueryRiskService implements QueryRiskUseCase` (read-only, delega ao port)

## 3. Infraestrutura

### Migration `V9__merchant_risk.sql`
```sql
CREATE TABLE merchant_risk (
    id                     UUID PRIMARY KEY,
    merchant_id            UUID          NOT NULL REFERENCES merchants(id),
    computed_at            TIMESTAMP     NOT NULL,
    approved_volume        NUMERIC(15,2) NOT NULL,
    total_transactions     BIGINT        NOT NULL,
    approved_transactions  BIGINT        NOT NULL,
    refunded_transactions  BIGINT        NOT NULL,
    refund_rate_percent    NUMERIC(6,2)  NOT NULL,
    chargeback_rate_percent NUMERIC(6,2) NOT NULL DEFAULT 0,
    complaints_count       INT           NOT NULL DEFAULT 0,
    refund_alert           BOOLEAN       NOT NULL DEFAULT FALSE,
    chargeback_alert       BOOLEAN       NOT NULL DEFAULT FALSE,
    complaints_alert       BOOLEAN       NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_merchant_risk_merchant_id ON merchant_risk(merchant_id);
CREATE INDEX ix_merchant_risk_computed_at ON merchant_risk(computed_at);
```

### Persistência
- `MerchantRiskJpaEntity` + `MerchantRiskMapper` + `MerchantRiskJpaRepository`
  (`JpaSpecificationExecutor` + `findTopByMerchantIdOrderByComputedAtDesc`).
- `MerchantRiskPersistenceAdapter` (Specifications p/ `search`).

### Job agendado — `RiskMonitoringScheduler`
`@Scheduled(cron = "${foxcash.scheduler.risk-cron:0 0 3 * * *}", zone="UTC")`
+ `@SchedulerLock(name = "foxcash-risk-monitoring", lockAtMostFor = "PT10M")`
(padrão Specs 6/7).

### Config — `RiskProperties` (`foxcash.risk.*`)
`chargeback-alert-percent` (1.0), `chargeback-suspend-percent` (3.0),
`refund-alert-percent` (5.0), `complaints-alert-count` (3) — registrado no
`ApplicationConfig.@EnableConfigurationProperties`; defaults em
`application.yml` (e `application-prod.yml` sem mudança — env vars opcionais).

### REST — `MasterRiskController` (`/api/v1/master`, role `ROLE_MASTER`)
- `GET /risk` → `PageResponse<MerchantRiskResponse>` (filtro `merchantId`,
  parsing via `QueryParamParser`).
- `GET /merchants/{merchantId}/risk` → snapshot mais recente; sem snapshot →
  **404 `risk_snapshot_not_found`**.
- `POST /merchants/{merchantId}/suspend` → **204**.
- `POST /merchants/{merchantId}/release` → **204** (lojista inexistente →
  404 `merchant_not_found`).

### DTOs
- `MerchantRiskResponse` (campos do snapshot) + `from(MerchantRiskSnapshot)`.
- Exceção `RiskSnapshotNotFoundException` → handler 404 no
  `GlobalExceptionHandler`.

## 4. Testes (unit, Mockito)
- `RiskMonitoringServiceTest` (~6): taxa de reembolso correta; flag de alerta
  acima/abaixo do limiar; volume aprovado somado; chargeback/complaints zerados;
  lojista sem transações (taxa 0); grava um snapshot por lojista.
- `MerchantStatusControlServiceTest` (~3): suspend/release persistem;
  lojista inexistente → 404.
- `QueryRiskServiceTest` (~2): delegação de `search`/`latestByMerchant`.

## Fora de escopo (follow-up)
- Alimentação de chargeback/denúncias (endpoint administrativo de disputa).
- Suspensão automática por risco (decisão permanece do master).
- Reserva de valor (§7) e bloqueio permanente.
