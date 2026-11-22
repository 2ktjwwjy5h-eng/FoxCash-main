---
name: Reconciliação e integridade do saldo — job de validação + robustez de contrato
status: audited
references:
  - "management/vision.md#V18"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/8-admin-dashboard/requirements.md"
  - "management/specs/25-merchant-sales/requirements.md"
---

# Spec 30 — Reconciliação e integridade do saldo

## O que entrega

O **backstop** da Fase 5: mesmo com o ledger (Spec 27) e o estorno consistente
(Spec 29), um erro silencioso futuro de gravação de movimentação precisa ser
**detectado**. Esta spec adiciona:

1. **Job de reconciliação** (ShedLock): valida, para cada lojista, que
   `merchants.wallet_balance == balance_after da última entry do ledger`
   (Spec 27). Divergência → registra issue em `reconciliation_issues`
   (migration V30) com valores esperado/atual, para auditoria do master.
2. **Execução manual:** `POST /api/v1/master/reconciliation/run` (ROLE_MASTER)
   roda a validação na hora e retorna o resumo; `GET
   /api/v1/master/reconciliation/issues` lista as divergências (paginado,
   filtros `merchantId`/`status`).
3. **Fatura via COUNT** (corrige **L3**): `GenerateInvoiceService` passa a usar
   `countByMerchantIdAndStatusAndCreatedAtBetween(merchantId, APPROVED, from, to)`
   (já existe, Spec 25) em vez de carregar todas as transações com `.size()`.
   **Decisão de negócio explicitada (M2):** a fatura é **snapshot do período** —
   transações estornadas após a geração não ajustam a fatura (ajuste de fatura
   fica documentado como spec futura).
4. **Escala normalizada no JSON** (corrige **L2**): as respostas financeiras do
   portal e do master serializam `BigDecimal` com escala 2 (`150.00`, não
   `150.0`) — contrato estável para o frontend.

## Critérios de aceite

1. `POST /api/v1/master/reconciliation/run` executa a validação de todos os
   lojistas e retorna `{checked, ok, issues}` (200, ROLE_MASTER).
2. Sem divergência → nenhuma issue; com divergência **simulada** (UPDATE direto
   em `merchants.wallet_balance` sem ledger) → issue registrada em
   `reconciliation_issues` com valores `expectedBalance`/`actualBalance`.
3. `GET /api/v1/master/reconciliation/issues` paginado com filtros
   `merchantId`/`status` (400 em parâmetro inválido, 401 sem token, 403 com
   role errada).
4. Scheduler agendado (ShedLock `foxcash-wallet-reconciliation`, cron
   configurável) roda a mesma validação; re-execução não duplica issues
   (idempotente — 1 issue aberta por lojista divergente).
5. `GenerateInvoiceService` gera a fatura com **COUNT** (não carrega a lista de
   transações — verificável por query/teste) e o valor total permanece
   idêntico ao atual.
6. Respostas financeiras do portal (métricas/summary/cash-flow/ledger) e do
   master serializam valores com escala 2 (ex.: `volume30d: 150.00`).
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário de
   reconciliação manual).

## Dependências

- Spec 27 (ledger — fonte de verdade para o saldo esperado).
- Spec 25 (contagem por status no intervalo — reutilizada na fatura).
- Spec 8 (faturas — cálculo e regra de snapshot).
- Spec 6 (padrão ShedLock do scheduler).

## Observações

- **Decisão (M2):** fatura é snapshot — a taxa R$ 0,50 é cobrada sobre as
  transações aprovadas no período **no momento da geração**; estorno posterior
  devolve o valor ao comprador mas não ajusta a fatura já gerada. Fatura de
  ajuste (crédito ao lojista) fica para spec futura (referenciada).
- **Recuperação automática** de divergência está fora de escopo: a issue é
  registrada e o master decide (correção manual documentada no design).
- **Webhook `REFUNDED` de `PENDING` (órfão, Spec 29):** transações `PENDING`
  sem `externalReference` são reportadas no resumo do run como candidatas a
  cobrança órfã (timeout da Spec 28) — visibilidade sem ação automática.
