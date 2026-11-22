---
name: Financeiro rico — resumo de Pix, transações com gateway e resumo do período (backend)
status: verified
---

# Spec 105 — Design: Financeiro rico (backend)

## D1 — Conceito

Um **`PortalFinancialService`** (aplicação) que compõe agregados existentes com
**filtro temporal centralizado** (`PeriodFilter`). Reusa a lógica de
`customerName`/`productName`/`netAmount` da Spec 97 (helper/porta extraída —
DRY).

```text
PortalFinancialController (REST, ROLE_MERCHANT_PORTAL)
   └─ PortalFinancialService (aplicacao)
        ├─ TransactionRepository — COUNT/SUM por status/period + gateway/flow
        ├─ LeadResolver (Spec 97 D5) — customerName
        └─ PlanRepository — productName
```

## D2 — PeriodFilter

```java
enum PortalPeriod { HOJE, ONTEM, SEMANA, MES, TODO }
record PeriodRange(Instant from, Instant to) // UTC
```

`Parser` converte com `Clock` (Spec 22). `semana` = desde a última segunda
00:00 UTC (decisão); `mes` = dia 1 00:00 UTC; `todo` = `(null, null)`.

## D3 — Endpoints

### D3.1 `GET /api/v1/portal/financial/summary?period=`

`{pixGenerated, pixPaid, pixPending, grossVolume, methodsPixPercent,
averageTicket, expiredCount, refundCount, uniqueLeads}`.

- Pix: `pixGenerated` = COUNT criados; `pixPaid` = COUNT APPROVED;
  `pixPending` = COUNT PENDING (Spec 25 statuses reais).
- `grossVolume` = SUM APPROVED (janela). `methodsPixPercent` = 100 quando
  `grossVolume > 0` (só Pix hoje) — decisão: 100 ou 0 sem vendas.
- `expiredCount` = COUNT EXPIRED (status real — verificar `TransactionStatus`);
  `refundCount` = COUNT REFUNDED + COUNT chargebacks (Spec 29).
- `uniqueLeads` = COUNT DISTINCT `bot_leads` (janela, Spec 83).
- `averageTicket` = `grossVolume / pixPaid` (0 sem pagos).

### D3.2 `GET /api/v1/portal/transactions`

`PageResponse<PortalFinancialTransaction>`: `{transactionId, customerName |
buyerIdHash, productName, gateway, status, flow, amount, netAmount,
createdAt}`.

- `gateway` = `provider` (Spec 91; null → "PADRAO").
- `flow` = derivado do status/chargeback (`SALE` para APPROVED/PENDING;
  `REFUND` para REFUNDED; `CHARGEBACK` para transação com chargeback).
- Filtros: `period`, `gateway`, `status`, `flow`, `page`/`size` (cap 100).
- `netAmount` via `PricingCalculator` (Spec 25) — considera override da Spec 98
  quando implementado.

## D4 — Persistência

Nenhuma migration. Índices existentes (Specs 44/46) cobrem os agregados por
período.

## D5 — Testes

**Unitários:** `PortalFinancialServiceTest` (períodos — hoje/ontem/semana/mes/
todo; resumo com e sem dados; averageTicket 0; transações com gateway/flow;
filtros combinados). **Integração:** controller 200/400/401/403. **E2E:**
`portal-financial.spec.js` — summary + transactions com gateway/fluxo/bruto/
líquido e filtros.

## D6 — Não ignorar a Spec 139 (escopo por perfil)

Transações têm `profile_id` (V70) + `provider` (V58). Resumo e tabela são
**escopados pelo perfil ativo** (Spec 124 — `X-Profile-ID` via
`ProfileScopeResolver`); respeitar a lente Bot/Gateway (Spec 139).
