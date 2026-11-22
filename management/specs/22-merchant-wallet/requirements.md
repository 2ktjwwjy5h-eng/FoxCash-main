---
name: Carteira/financeiro do lojista — saldo, resumo do mês, entradas da semana, fluxo de caixa
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
---

# Spec 22 — Carteira/financeiro do lojista (backend-first)

## O que entrega

APIs da tela **Carteira/Financeiro** do portal do lojista — leitura do saldo,
do resumo do mês e do fluxo de caixa:

1. **Saldo disponível:** `wallet_balance` do lojista (fonte oficial, já usada
   em saques — Spec 14) + flag `withdrawals_blocked`.
2. **Resumo do mês:** receita bruta (volume `APPROVED`), receita **líquida**
   (bruta − taxas de transação do período) e contagem de transações.
3. **Entradas da semana (barras):** série de `TimeSeriesPoint {label, value}`
   com volume aprovado por dia nos últimos 7 dias.
4. **Fluxo de caixa:** entradas (volume aprovado) vs saídas (total de saques
   `COMPLETED` — Spec 14) no período.
5. **Rodapé de transparência:** campos calculados pela API (`saldoCalculadoPelaApi: true`,
   `origem: "fluxo oficial"`) — o frontend exibe "Saldo calculado pela API" e
   "Segue no fluxo oficial", deixando explícito que o valor não é estimativa
   local.

## Critérios de aceite

1. `GET /api/v1/portal/wallet/balance` → `{walletBalance, withdrawalsBlocked,
   saldoCalculadoPelaApi: true}` — exige `ROLE_MERCHANT_PORTAL`.
2. `GET /api/v1/portal/wallet/monthly-summary?month=yyyy-MM` →
   `{grossRevenue, netRevenue, transactionCount}` — líquida = bruta − taxas de
   transação do período (faturas, Spec 8); mês inválido → `400
   invalid_argument`.
3. `GET /api/v1/portal/wallet/weekly-inflows` → 7 pontos `{label, value}` (dia
   da semana × volume aprovado, UTC, zerado quando sem vendas).
4. `GET /api/v1/portal/wallet/cash-flow?from=&to=` → `{inflows, outflows,
   net}` — saídas = soma de saques `COMPLETED` (Spec 14); períodos inválidos →
   `400 invalid_argument`.
5. Todos os endpoints exigem `ROLE_MERCHANT_PORTAL` (401/403); `mvn test`
   verde (**~147 testes: 141 + ~6 novos** — `MerchantWalletServiceTest`) e E2E
   sem regressão.

## Dependências

- Spec 20 (auth do portal + convenções).
- Spec 14 (saques: `outflows`, `withdrawalsBlocked`).
- Spec 15 (agregações `SUM`/`COALESCE` de transações aprovadas e faturas).

## Observações

- **Somente leitura** — nenhuma escrita; o saldo é sempre o calculado pela API
  (fluxo oficial), nunca estimativa do frontend.
- **Frontend fica para spec futura** — o contrato REST é o entregável.
