---
name: Dashboard do lojista — visão geral (métricas, série de faturamento, últimas vendas, pontos de atenção)
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
---

# Spec 21 — Dashboard do lojista: visão geral (backend-first)

## O que entrega

APIs de **visão geral** para a tela principal do portal do lojista — leitura
agregada do que o lojista precisa ver ao abrir o painel:

1. **Métricas de topo (MetricCards):** receita bruta (período), vendas
   (contagem) e **conversão PIX** (pagas ÷ criadas, %). Período padrão: mês
   corrente, com `from`/`to` opcionais.
2. **Série de faturamento (TimeSeries):** pontos `{label, value}` para o
   gráfico de linha (faturamento por dia no período).
3. **Últimas vendas:** lista reduzida (últimas N transações, default 5) com
   `transactionId`, valor, status, data — reuso do domínio de transações
   (Spec 8/15).
4. **Pontos de atenção:** painel de alertas derivados dos indicadores de risco
   (Spec 16/19) e do estado do lojista — ex.: chargeback em alerta, reembolso
   alto, saque bloqueado, documento pendente. Leitura, sem decisão (decisão é
   do master).

**Decisão (confirmada 2026-08-04):** "Jornada do player" = **gamificação do
lojista** — barra de progresso com metas de volume. Entra como
`journeyProgress` (0–100) no summary, calculado por regra configurável
(`foxcash.portal.journey-goal-*`): progresso = volume aprovado no período ÷
meta × 100 (cap 100).

## Critérios de aceite

1. `GET /api/v1/portal/dashboard/summary?from=&to=` → `{grossRevenue, sales,
   pixConversionRate, journeyProgress}` — conversão = pagas/criadas×100
   (0 se sem criadas); períodos inválidos → `400 invalid_argument`; exige
   `ROLE_MERCHANT_PORTAL` (Spec 20).
2. `GET /api/v1/portal/dashboard/revenue-series?from=&to=` → lista de
   `TimeSeriesPoint {label, value}` (faturamento por dia, UTC; dias sem venda
   com valor 0).
3. `GET /api/v1/portal/dashboard/recent-sales?limit=` → últimas transações do
   lojista (`{transactionId, amount, status, createdAt, buyerIdHash?}`) —
   `limit` clamp 1–50, default 5.
4. `GET /api/v1/portal/dashboard/attention-points` → lista de alertas
   `{type, severity, message}` derivados de risco/estado (chargeback ≥ limiar
   de alerta, reembolso ≥ 5%, `withdrawals_blocked`, status ≠ ACTIVE); vazio
   quando nada a apontar.
5. Todos os endpoints exigem `ROLE_MERCHANT_PORTAL` (401 sem token, 403 com
   API key); `mvn test` verde (**~141 testes: 135 + ~6 novos** —
   `MerchantDashboardServiceTest`) e E2E sem regressão.

## Dependências

- Spec 20 (auth do portal + convenções `MetricCard`/`TimeSeriesPoint`).
- Spec 8/15 (agregações `SUM`/`COALESCE`, paginação).
- Spec 16/19 (indicadores de risco para pontos de atenção).

## Observações

- **Somente leitura** — nenhuma escrita no domínio financeiro.
- **`buyerIdHash` nunca é devolvido em claro** (Spec 19): se a UI precisar
  identificar o pagador, usa-se o hash ou um rótulo opaco — confirmar com o
  usuário o que a coluna "pagador" mostra.
- **Frontend fica para spec futura** — o contrato REST é o entregável.
