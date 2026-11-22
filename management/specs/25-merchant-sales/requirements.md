---
name: Vendas/operação comercial — listagem com filtros e métricas de topo
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/15-master-dashboard/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
---

# Spec 25 — Vendas/operação comercial (backend-first)

## O que entrega

APIs da tela **Vendas/Operação Comercial** do portal do lojista — listagem de
vendas com filtros, métricas de topo e ações:

1. **Tabela de vendas:** `GET /api/v1/portal/sales?status=&from=&to=&page=&size=`
   → paginada (reuso de `PageResponse`) com `{transactionId, buyerIdHash?,
   method: "PIX", grossAmount, netAmount, status, createdAt}` — status
   `PENDING`/`APPROVED` (exibido como "pendente"/"concluído").
2. **Métricas de topo:** volume em 30 dias (soma `APPROVED`), vendas
   concluídas, vendas pendentes, taxa de conversão — no mesmo contrato
   `MetricCard` da Spec 20.
3. **Filtros:** `Todas | Concluídas | Pendentes` + janela de datas; parâmetros
   inválidos → `400 invalid_argument`.
4. **Ações por venda:** leitura de detalhe (`GET /api/v1/portal/sales/{id}` →
   payload Pix completo quando `PENDING`); **estorno/reembolso** fica fora de
   escopo (decisão do master/PSP, Spec 16) — apenas consulta nesta spec.

**Decisão (confirmada 2026-08-04):** a coluna "pagador" exibe o
`buyerIdHash` (rótulo opaco SHA-256) — o gateway é somente para o vendedor,
sem PII do comprador (Spec 19). Quando o lojista não envia `buyerId`, a
coluna fica vazia ("—").

## Critérios de aceite

1. `GET /api/v1/portal/sales` paginado com filtros `status` (`PENDING`/
   `APPROVED`), `from`/`to`, `page`/`size` (default 0/20, max 100) → itens com
   `{transactionId, buyerIdHash?, method: "PIX", grossAmount, netAmount,
   status, createdAt}`; parâmetro inválido → `400 invalid_argument`; exige
   `ROLE_MERCHANT_PORTAL`.
2. `netAmount = grossAmount − taxa` (0,50/transação do plano — mesmo cálculo
   das faturas, Spec 8); transação de outro lojista → `403` (autorização por
   recurso, Spec 18).
3. `GET /api/v1/portal/sales/metrics?days=30` → `{volume30d, completedSales,
   pendingSales, conversionRate}` — volume = soma `APPROVED` nos últimos N
   dias (default 30, clamp 1–90).
4. `GET /api/v1/portal/sales/{transactionId}` → detalhe (payload Pix incluído
   quando `PENDING`; `buyerIdHash` se houver); inexistente → `404
   transaction_not_found`.
5. `mvn test` verde (**204 testes: 196 + 8 novos** —
   `MerchantSalesServiceTest`) e E2E sem regressão (**10/10**, cenário 10 novo
   cobrindo listagem/filtros/métricas/detalhe/400/404).

## Dependências

- Spec 20 (auth do portal + convenções `MetricCard`/`PageResponse`).
- Spec 15 (agregações `SUM`/`COALESCE` de transações).
- Spec 19 (hash do comprador, sem PII).
- Spec 18 (autorização por recurso).

## Observações

- **Somente leitura + detalhe** — estorno/reembolso fora de escopo (decisão do
  master/PSP).
- **Sem PII do comprador** (Spec 19): "pagador" é exibido como hash/rótulo
  opaco.
- **Frontend fica para spec futura** — o contrato REST é o entregável.
