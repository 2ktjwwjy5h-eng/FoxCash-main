# Spec 25 — Design: Vendas/operação comercial (backend-first)

## Contexto

O portal do lojista (Spec 20) já tem dashboard (21), carteira (22), cobrança
(23) e credenciais (24). Falta a tela **Vendas/Operação Comercial**: listagem
paginada das transações do lojista com filtros (status + janela de datas),
métricas de topo (volume 30d, concluídas, pendentes, conversão) e leitura de
detalhe (payload Pix quando `PENDING`). Somente leitura — estorno/reembolso
fica fora de escopo (decisão do master/PSP, Spec 16).

Reusa integralmente as convenções das Specs 20–24:

- Role `ROLE_MERCHANT_PORTAL` + principal = `merchantId`
  (`SecurityConfig` já protege `/api/v1/portal/**` — sem mudança de security).
- Paginação `PageResult`/`PageResponse` + `QueryParamParser`
  (`page` default 0, `size` default 20/max 100, parâmetros inválidos → 400
  `invalid_argument`).
- Autorização por recurso (Spec 18/ADR-009): transação de outro lojista →
  `MerchantAccessDeniedException` → 403 `forbidden`.
- Sem PII do comprador (Spec 19): `buyerIdHash` opaco; `null` quando o bot não
  enviou.
- `netAmount = grossAmount − taxa` — mesma fonte das faturas (Spec 8):
  `merchant.pricingPlan()` via `PricingCalculator` (R$ 0,50/transação).
- `Clock` injetado (padrão Spec 22) para a janela "últimos N dias" das métricas.

## Decisões de arquitetura

### Porta de saída `TransactionRepository` (evoluída)

Novos métodos (o port já cresceu assim na Spec 16):

```java
// Lista paginada de vendas do lojista com filtros opcionais (Spec 25).
PageResult<PixTransaction> searchSales(UUID merchantId, TransactionStatus status,
                                       Instant from, Instant to, int page, int size);

// Métricas (janela de datas):
long countByMerchantIdAndCreatedAtBetween(UUID merchantId, Instant from, Instant to);
long countByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId, TransactionStatus status,
                                                   Instant from, Instant to);
BigDecimal sumAmountByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId,
                                                             TransactionStatus status,
                                                             Instant from, Instant to);
```

- `searchSales`: `Specification` dinâmica sobre `PixTransactionJpaEntity`
  (merchantId obrigatório; status/from/to opcionais) + `PageRequest` ordenado
  por `createdAt DESC` — mesmo padrão do `MasterDashboardPersistenceAdapter`
  (Spec 15). `PixTransactionJpaRepository` já estende
  `JpaSpecificationExecutor`.
- Contagens e soma: derived queries + `@Query COALESCE(SUM(...), 0)` no
  PostgreSQL (padrão Spec 15/16).

### Porta de entrada `MerchantSalesUseCase` (nova)

```java
PageResult<Sale> list(UUID merchantId, TransactionStatus status,
                      Instant from, Instant to, int page, int size);
Metrics metrics(UUID merchantId, int days);          // clamp 1–90, default 30
SaleDetail detail(UUID merchantId, UUID transactionId);

record Sale(UUID transactionId, String buyerIdHash, Money grossAmount,
            Money netAmount, TransactionStatus status, Instant createdAt)
record Metrics(BigDecimal volume30d, long completedSales, long pendingSales,
               BigDecimal conversionRate)
record SaleDetail(UUID transactionId, String buyerIdHash, Money grossAmount,
                  Money netAmount, TransactionStatus status, Instant createdAt,
                  Instant paidAt, String description, String pixKey,
                  String pixQrCode, String pixCopyPaste, String endToEndId)
```

- `list`: delega ao port e mapeia cada item com
  `netAmount = grossAmount − transactionFee(merchant.pricingPlan())`
  (taxa calculada uma única vez por requisição).
- `metrics`: `days` clamp 1–90; janela `[now − days, now]` via `Clock`;
  volume = `SUM APPROVED`; `completedSales`/`pendingSales` = contagens por
  status; `conversionRate = completed / totalCriadasNoPeriodo × 100`
  (mesma fórmula do dashboard, Spec 21; `0` quando não há transações).
- `detail`: `findById` → inexistente `TransactionNotFoundException` (404);
  `merchantId` diferente do principal → `MerchantAccessDeniedException` (403);
  payload Pix incluído **somente quando `PENDING`** (requisito), campos pix
  nulos nos demais status.

### Aplicação `MerchantSalesService` (nova)

`@Service @Transactional(readOnly = true)`; injeta `TransactionRepository`,
`MerchantRepository`, `PricingCalculator` e `Clock`.

### REST `PortalSalesController` (novo, `ROLE_MERCHANT_PORTAL`)

`@RequestMapping("/api/v1/portal/sales")` — a auth já existe no
`SecurityConfig` (`/api/v1/portal/**`).

| Rota | Resposta |
|---|---|
| `GET /api/v1/portal/sales?status=&from=&to=&page=&size=` | `PageResponse<PortalSaleResponse>` |
| `GET /api/v1/portal/sales/metrics?days=30` | `PortalSalesMetricsResponse` |
| `GET /api/v1/portal/sales/{transactionId}` | `PortalSaleDetailResponse` |

- `{transactionId}` malformado → `MethodArgumentTypeMismatchException` → 400
  `invalid_argument` (handler existente).
- `GET /sales/metrics` ganha do path variable por precedência de literal do
  Spring MVC; `{transactionId}` é UUID, "metrics" nunca colide.

### DTOs (novos, `infrastructure/inbound/rest/dto`)

- `PortalSaleResponse {transactionId, buyerIdHash, method:"PIX", grossAmount,
  netAmount, status, createdAt}`.
- `PortalSalesMetricsResponse {volume30d, completedSales, pendingSales,
  conversionRate}`.
- `PortalSaleDetailResponse {transactionId, buyerIdHash, method:"PIX",
  grossAmount, netAmount, status, createdAt, paidAt, description, pixKey,
  pixQrCode, pixCopyPaste, endToEndId}` — campos pix nulos fora de `PENDING`.

## Arquivos

- `domain/port/in/MerchantSalesUseCase.java` (novo)
- `domain/port/out/TransactionRepository.java` (4 métodos novos)
- `application/service/MerchantSalesService.java` (novo)
- `infrastructure/outbound/persistence/repository/PixTransactionJpaRepository.java`
  (derived queries + `@Query` SUM)
- `infrastructure/outbound/persistence/adapter/TransactionPersistenceAdapter.java`
  (implementa os 4 métodos)
- `infrastructure/inbound/rest/PortalSalesController.java` (novo)
- `infrastructure/inbound/rest/dto/PortalSaleResponse.java`,
  `PortalSalesMetricsResponse.java`, `PortalSaleDetailResponse.java` (novos)
- Teste: `application/service/MerchantSalesServiceTest.java` (novo, ~7–8 testes)
- E2E: cenário 10 em `e2e-tests/tests/merchant-journey.spec.js` (listagem,
  filtros, métricas, detalhe PENDING, 400, 404)

## Fora de escopo

- Estorno/reembolso (decisão do master/PSP, Spec 16).
- Frontend (spec futura — o contrato REST é o entregável).
- Novas tabelas/migrations (só leitura sobre `pix_transactions` existente).
- Filtro de status restrito a PENDING/APPROVED no parse: o `QueryParamParser`
  aceita qualquer `TransactionStatus` válido (garbage → 400); a UI oferece
  `Todas | Concluídas | Pendentes`.
