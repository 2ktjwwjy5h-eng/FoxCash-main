# Spec 15 — Design (Dashboard master: transações, ganhos e taxas)

## 1. Domínio

### Modelos (somente-leitura)
- `domain/model/master/MasterEarnings`:
  `(Money walletBalance, Money approvedVolume, Money totalWithdrawn,
  boolean withdrawalsBlocked)`.
- `domain/model/master/MasterFees`:
  `(int transactionCount, Money transactionFees, Money monthlyFee,
  Money totalFees)`.
- `domain/model/master/MasterTransactionSearchQuery`:
  `(UUID merchantId, TransactionStatus status, Instant from, Instant to,
  int page, int size)`.

### Portas
- `domain/port/in/MasterDashboardUseCase`:
  `PageResult<PixTransaction> transactions(MasterTransactionSearchQuery)`,
  `MasterEarnings earnings(UUID merchantId)`,
  `MasterFees fees(UUID merchantId, BillingPeriod period)`.
- `domain/port/out/MasterDashboardPort` (idem, com `findTransactions`).

## 2. Aplicação

`MasterDashboardService implements MasterDashboardUseCase`
(`@Transactional(readOnly = true)`):
- `transactions` → delega ao port (reuso do padrão Spec 8).
- `earnings`/`fees` → primeiro valida existência do lojista
  (`MerchantNotFoundException` → 404), depois delega ao port.

## 3. Infraestrutura

### `MasterDashboardPersistenceAdapter implements MasterDashboardPort`
- `findTransactions`: `PixTransactionJpaRepository.findAll(Specification,
  PageRequest)` — filtros `merchantId`/`status`/`createdAt between`, ordenado
  por `createdAt DESC` (mesmo padrão do `AdminDashboardPersistenceAdapter`).
- `earnings(merchantId)`:
  - `walletBalance` + `withdrawalsBlocked` do `MerchantJpaRepository`;
  - `approvedVolume` = `SUM(amount)` de `APPROVED` por lojista;
  - `totalWithdrawn` = `SUM(amount)` de saídas `COMPLETED` por lojista.
- `fees(merchantId, period)`:
  - `InvoiceJpaRepository.findByMerchantIdAndBillingPeriod` (já existe);
  - sem fatura no período → zeros (`0`, `0.00`, `0.00`, `0.00`);
  - com fatura → `transactionCount`, `transactionFeesTotal`, `monthlyFee`,
    `totalAmount` (fees + mensalidade).

### Repositórios (métodos novos)
- `PixTransactionJpaRepository`:
  `@Query SUM(amount) WHERE merchantId = :merchantId AND status = :status`.
- `WithdrawalJpaRepository`:
  `@Query COALESCE(SUM(amount), 0) WHERE merchantId = :merchantId AND status =
  'COMPLETED'`.

### REST — `MasterDashboardController` (`/api/v1/master`, role `ROLE_MASTER`)
- `GET /transactions` → `PageResponse<PixTransactionResponse>` (reuso).
- `GET /merchants/{merchantId}/earnings` → `MasterEarningsResponse`.
- `GET /merchants/{merchantId}/fees?month=yyyy-MM` → `MasterFeesResponse`
  (mês inválido → 400 `invalid_argument`).
- Parsing de parâmetros: helper compartilhado com o `MasterWithdrawalController`
  (Spec 14) — `infrastructure/inbound/rest/QueryParamParser` (package-private).

### DTOs
- `MasterEarningsResponse(walletBalance, approvedVolume, totalWithdrawn,
  withdrawalsBlocked)`.
- `MasterFeesResponse(transactionCount, transactionFees, monthlyFee,
  totalFees)`.

## 4. Testes (unit, Mockito)
- `MasterDashboardServiceTest` (~6): transactions paginado; earnings com soma
  de aprovados + saídas + flag; fees com fatura; fees sem fatura (zeros);
  lojista inexistente → 404; delegação correta.

## Fora de escopo
- Escrita no domínio financeiro (exceto controles de saque da Spec 14).
- Paginação nova: mesmo padrão Spec 8 (default 0/20, máx 100).
