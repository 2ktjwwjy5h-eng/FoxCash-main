---
name: Faturamento automático e contas a receber — cobrança da fatura no wallet e dívida formal
status: audited
references:
  - "management/vision.md#V20"
  - "management/specs/6-invoice-scheduler/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/29-consistent-refund/requirements.md"
---

# Spec 33 — Faturamento automático e contas a receber

## O que entrega

Hoje a fatura mensal (Spec 6/8) é gerada mas **ninguém paga**: o sistema nunca
debita o wallet no vencimento, o lojista pode sacar todo o saldo e a
plataforma não recebe as taxas (R$ 0,50/venda) nem a mensalidade (R$ 69,95).
Diagnóstico Fase 6 (**A3/A2**).

Esta spec fecha o ciclo de faturamento:

1. **Cobrança automática no vencimento:** job diário (ShedLock
   `foxcash-invoice-collection`) cobra faturas não quitadas e vencidas
   (`PENDING` ou `OVERDUE` com saldo devedor) direto do wallet do lojista.
   Saldo suficiente → debita tudo e marca `PAID`; saldo insuficiente → debita o
   **disponível** (pagamento parcial) e formaliza a **dívida restante**.
   Re-execução re-cobra `OVERDUE` quando o lojista volta a ter saldo.
2. **Contas a receber (dívida formal):** tabela `receivables` (migration V22)
   registra todo débito do lojista com a plataforma com origem explícita:
   `INVOICE` (fatura não coberta), `CHARGEBACK`/`REFUND` (estorno que deixou o
   saldo negativo — decisão Spec 26/29), `ADJUSTMENT` (reservado). Status
   `OPEN`/`PAID`/`PARTIAL`, com `paid_amount` rastreável.
3. **Inadimplência bloqueia saque:** lojista com receivable `OPEN` → saques do
   vendedor respondem **409 `outstanding_debt`** (configurável
   `foxcash.billing.block-withdrawals-on-debt`, default `true`). O master
   continua podendo recolher via saque administrativo (Spec 32).
4. **Controle do master:** `POST
   /api/v1/master/merchants/{id}/invoices/{invoiceId}/collect` cobra na hora
   (manual); `GET /api/v1/master/receivables` e
   `GET /api/v1/master/merchants/{id}/receivables` listam as dívidas
   (paginado/filtros).
5. **Ledger (Spec 27):** toda cobrança registra entry `INVOICE_FEE_DEBIT` no
   wallet do lojista; a receita da plataforma fica para a Spec 34.

## Critérios de aceite

1. Job diário (ou `collect` manual) processa fatura não quitada e vencida
   (`PENDING` ou `OVERDUE` com saldo devedor): saldo suficiente → debita o total
   + invoice `PAID` + 1 entry `INVOICE_FEE_DEBIT` no ledger; saldo insuficiente
   → debita o disponível, invoice `OVERDUE` e receivable `OPEN` com o saldo
   devedor. O mesmo job quita receivables de estorno (`CHARGEBACK`/`REFUND`)
   com o saldo disponível (fecha o ciclo da dívida — Spec 34 registra
   `RECEIVABLE_RECOVERY`).
2. **Pagamento parcial:** com saldo < fatura, o valor disponível é debitado,
   `receivables.paid_amount` registra o pago e o restante fica `OPEN`.
3. Lojista com receivable `OPEN` → `POST /withdrawals` → **409
   `outstanding_debt`** (e nenhuma rota do vendedor contorna; configurável).
4. **Idempotência:** re-execução do job não cobra 2× — invoice já
   `PAID`/`OVERDUE` é ignorada (sem duplicar débito/ledger/receivable).
5. Estorno não coberto (chargeback/refund com saldo negativo, Spec 29) →
   receivable `OPEN` origem `CHARGEBACK`/`REFUND` registrado na mesma transação
   do débito.
6. `GET /api/v1/master/receivables` paginado (filtros `merchantId`/`status`/
   `origin`/`from`/`to`), `400 invalid_argument` em inválido, `401`/`403` de
   auth.
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 13:
   cobrança automática, pagamento parcial, bloqueio por dívida, idempotência).

## Dependências

- Spec 6 (geração de faturas — a fatura passa a ser cobrada).
- Spec 27 (ledger — débito da cobrança registrado; base para a Spec 34).
- Spec 14 (controle de saques — novo motivo de bloqueio).
- Spec 29 (estorno — débito não coberto formalizado como receivable).

## Observações

- **Decisão de produto:** a cobrança é automática no vencimento; o lojista é
  notificado (Spec 35) antes e depois. O `collect` manual existe para o master
  agilizar/reprocessar.
- **Dívida vs. perda:** receivable `OPEN` é dívida (o lojista deve); a **baixa**
  (write-off) é ação do master (Spec 34/37) — vira perda `CHARGEBACK_LOSS`.
- **Reserva de valor** (retenção preventiva) fica fora de escopo (spec futura).
