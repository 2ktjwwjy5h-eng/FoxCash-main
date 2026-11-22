---
name: Ledger do master — receita da plataforma registrada na mesma transação
status: audited
references:
  - "management/vision.md#V20"
  - "management/specs/33-invoice-collection-receivables/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
---

# Spec 34 — Ledger do master (receita da plataforma)

## O que entrega

O ledger da Spec 27 blindou o saldo do **lojista**; a **plataforma** (o dono)
continua sem registro de receita. Hoje as taxas (R$ 0,50/venda + R$ 69,95/mês)
são cobradas (Spec 33) mas **não há nenhuma contabilização do que entra para a
plataforma**, nem das perdas (estornos não cobertos) nem dos recolhimentos
administrativos. Diagnóstico Fase 6 (**A1**).

Esta spec cria o **ledger do master** — receita/perda da plataforma registrada
**na mesma transação** do evento financeiro que a originou:

1. **Tabela `master_ledger`** (migration V23) com `entry_type`:
   - `TRANSACTION_FEE` (R$ 0,50 × vendas — fatura paga, Spec 33);
   - `MONTHLY_FEE` (R$ 69,95 — fatura paga);
   - `RECEIVABLE_RECOVERY` (receivable `OPEN`/`PARTIAL` que foi pago);
   - `ADMIN_RECOVERY` (saque administrativo do master, Spec 32);
   - `CHARGEBACK_LOSS` (baixa/write-off de receivable — dívida considerada
     perda pelo master).
2. **Registro na mesma transação:** fatura paga (33) → entries de taxa +
   mensalidade; receivable pago → `RECEIVABLE_RECOVERY`; saque administrativo
   (32) → `ADMIN_RECOVERY`; baixa (ação master) → `CHARGEBACK_LOSS` (auditada
   pela Spec 37).
3. **`balance_after` do master** calculado sob lock do merchant (serialização
   por origem — o master_ledger por lojista), mesmo padrão do ledger do lojista.
4. **Consultas:** `GET /api/v1/master/revenue/summary?from=&to=` (receita por
   tipo + total no período), `GET /api/v1/master/revenue/ledger` (movimentações
   paginadas, filtros `merchantId`/`type`/`from`/`to`).
5. **Baixa de receivable (write-off):** `POST
   /api/v1/master/receivables/{id}/write-off` `{reason}` (ROLE_MASTER) →
   receivable `PAID` (baixa) + entry `CHARGEBACK_LOSS` + auditoria (Spec 37).

## Critérios de aceite

1. Migration V23 aplicada: `master_ledger` com `merchant_id`, `entry_type`,
   `amount`, `balance_after`, `reference_type`/`reference_id`, `created_at`,
   índices `(merchant_id, created_at)` e `(reference_type, reference_id)` e
   unique por origem `(merchant_id, entry_type, reference_type, reference_id)`
   — o `entry_type` faz parte da chave porque **uma fatura paga gera 2 entries**
   (`TRANSACTION_FEE` + `MONTHLY_FEE`) com a mesma referência.
2. Fatura paga (Spec 33) → entries `TRANSACTION_FEE` (0,50 × count) +
   `MONTHLY_FEE` (69,95) na **mesma transação** do débito no wallet (verificado
   no banco: 2 entries por fatura paga).
3. Receivable de estorno pago (parcial ou total) → entry `RECEIVABLE_RECOVERY`
   no valor efetivamente recebido (receivable de origem `INVOICE` quitado junto
   com a fatura **não** gera recovery — a receita já está nas entries da fatura;
   sem dupla contagem).
4. Saque administrativo (Spec 32) → entry `ADMIN_RECOVERY` (valor recolhido).
5. `POST /receivables/{id}/write-off` → receivable baixado + entry
   `CHARGEBACK_LOSS` + registro em `master_audit_log` (Spec 37); sem `reason` →
   400.
6. `GET /api/v1/master/revenue/summary` e `GET /api/v1/master/revenue/ledger`
   (paginado) com `400 invalid_argument` em parâmetro inválido, `401`/`403`;
   resumo do período = Σ entries do período (consistente).
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 14:
   fatura paga gera receita, write-off gera perda, summary).

## Dependências

- Spec 33 (fatura paga → receita; receivable).
- Spec 27 (mesmo padrão de ledger imutável + `balance_after`).
- Spec 32 (saque administrativo → `ADMIN_RECOVERY`).
- Spec 37 (auditoria da baixa/write-off).

## Observações

- **Fonte da verdade do faturamento:** a receita só é registrada quando o
  dinheiro **efetivamente** entra (fatura paga do wallet, receivable pago,
  recolhimento) ou é **baixado como perda**. O lucro da plataforma por período
  = Σ entradas − Σ perdas.
- **`balance_after` do master por lojista** (evita corrida entre origens do
  mesmo lojista); um saldo global da plataforma é a soma por lojista.
- **Write-off** é ação do master com motivo obrigatório e auditoria — nunca
  automático (decisão de negócio).
