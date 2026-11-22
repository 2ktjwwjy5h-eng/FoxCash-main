---
name: Wallet ledger — movimentações financeiras imutáveis e auditáveis
status: audited
references:
  - "management/vision.md#V18"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/26-qa-hardening-financeiro/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
---

# Spec 27 — Wallet ledger (movimentações financeiras auditáveis)

## O que entrega

Hoje o saldo do lojista é um único campo `merchants.wallet_balance` (Spec 14)
mutado diretamente pelos fluxos de dinheiro — **sem histórico, sem auditoria e
sem forma de reconciliar**. Qualquer erro silencioso de crédito/débito é
invisível até o saque (diagnóstico Fase 5, achado **C3**).

Esta spec introduz o **ledger do wallet**: uma tabela de movimentações
**imutáveis** em que **todo crédito/débito do saldo é registrado na mesma
transação** do efeito financeiro, com valor, tipo, referência de origem
(transação/saque/chargeback) e **saldo resultante** (`balance_after`). O saldo
passa a ser **validável contra o histórico** — a base para auditoria e para a
reconciliação da Spec 30.

1. **Migration V17** cria `wallet_ledger` com constraint única de idempotência
   por origem: `(merchant_id, entry_type, reference_type, reference_id)` — cada
   fonte financeira gera **exatamente uma** movimentação por tipo (uma mesma
   origem pode gerar entries de tipos diferentes: ex. transação aprovada →
   `SALE_CREDIT` e, se estornada depois, `REFUND_DEBIT`).
2. **Backfill idempotente**: um runner cria a entry `OPENING_BALANCE` (=
   saldo atual) para cada lojista uma única vez, permitindo reconciliar o saldo
   contra o ledger desde o deploy.
3. **Gravadores** nos 4 pontos de mutação do saldo (mesma transação):
   - Webhook `PAID` → `SALE_CREDIT` (referência = transação);
   - Webhook `REFUNDED` → `REFUND_DEBIT` (referência = transação);
   - Chargeback → `CHARGEBACK_DEBIT` (referência = chargeback);
   - Saque → `WITHDRAWAL_DEBIT` (referência = saque).
4. **Consulta de auditoria** (somente leitura): master vê o ledger de qualquer
   lojista (`GET /api/v1/master/merchants/{id}/ledger`) e o lojista vê o
   próprio no portal (`GET /api/v1/portal/wallet/ledger`) — paginado, com
   filtros por período e tipo. Transparência radical (valor da plataforma).

**Contrato de resposta** (ambos os endpoints):
`PageResponse<{entryId, type, amount, balanceAfter, referenceType, referenceId,
createdAt}>`.

## Critérios de aceite

1. Migration V17 aplicada: tabela `wallet_ledger` com `entry_type`, `amount`,
   `balance_after`, `reference_type`, `reference_id`, `created_at`, índices
   `(merchant_id, created_at)` e `(reference_type, reference_id)` e constraint
   única `(merchant_id, entry_type, reference_type, reference_id)` (verificado
   no banco). A uniqueness **inclui `entry_type`**: uma transação aprovada gera
   `SALE_CREDIT` e, se estornada depois, `REFUND_DEBIT` — ambas referenciam a
   mesma transação, então a chave de origem precisa do tipo para não colidir.
2. Webhook `PAID` → saldo creditado **e** 1 entry `SALE_CREDIT` na mesma
   transação, com `balance_after` = saldo pós-crédito (replay de `PAID`
   continua no-op e não duplica entry).
3. Webhook `REFUNDED` → 1 entry `REFUND_DEBIT` com `balance_after` = saldo
   pós-débito (pode ser negativo — decisão Spec 26).
4. Chargeback → 1 entry `CHARGEBACK_DEBIT`.
5. Saque → 1 entry `WITHDRAWAL_DEBIT`.
6. Idempotência: reenvio do mesmo evento (mesma referência) **não** cria uma
   segunda entry (constraint única como backstop; comportamento documentado em
   caso de conflito concorrente).
7. Backfill: runner cria `OPENING_BALANCE` = `wallet_balance` atual para cada
   lojista **uma única vez** (re-boot não duplica).
8. `GET /api/v1/master/merchants/{id}/ledger` e
   `GET /api/v1/portal/wallet/ledger`: paginados, `400 invalid_argument` em
   parâmetro inválido, `403 forbidden` para ledger de outro lojista (portal),
   `401` sem token e `403` com API key em `/portal/**`.
9. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cobertura
   do ledger no cenário de webhook/chargeback/saque).

## Dependências

- Spec 14 (fluxo de saques — pontos de mutação do saldo).
- Spec 26 (locks pessimistas `findByIdForUpdate` — base para serializar o
  cálculo de `balance_after`).
- Spec 19 (registro de chargeback — origem de débito).
- Spec 18 (autorização por recurso + convenções `PageResponse`/`ErrorResponse`).

## Observações

- **Escopo mínimo:** o ledger registra movimentações novas a partir do deploy;
  o histórico passado não é reconstruído (decisão) — o `OPENING_BALANCE` ancla
  a reconciliação desde o deploy.
- **`balance_after` imutável:** calculado sob o lock pessimista do merchant
  (mesmo fluxo já existente) e nunca editado depois.
- **Reconciliação (Spec 30)** usa esta spec para validar
  `wallet_balance = balance_after da última entry` — é o backstop de qualquer
  esquecimento futuro de gravação de ledger.
- **Sem novas tabelas de projeção:** apenas o ledger; as consultas agregadas
  existentes (Specs 15/22) continuam funcionando sobre `wallet_balance`.
