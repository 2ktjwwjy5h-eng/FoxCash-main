---
name: Taxa de saque R$ 2,50
status: audited
references:
  - "management/vision.md#V65"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/70-auto-withdraw/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
---

# Spec 88 — Taxa de saque R$ 2,50

## O que entrega

Adiciona uma **taxa fixa de R$ 2,50 por saque do lojista** à precificação da
plataforma (que hoje é R$ 0,50 por transação aprovada + R$ 69,95 mensais). A
taxa é **debitada no wallet do lojista no momento da solicitação do saque**
(junto com o valor), entra como **receita da plataforma** no ledger do master e
é **devolvida integralmente** se o saque falhar no PSP.

Decisões do usuário (2026-08-13):

| Aspecto | Decisão |
|---|---|
| Escopo | Todo saque do **lojista** paga R$ 2,50 — **manual** (Specs 14/32/36) **e automático** (Spec 70) |
| Falha no PSP | Estorno devolve **valor + taxa**; a receita da taxa é revertida (padrão `ADMIN_RECOVERY_REVERSAL`, SEC-29) |
| Saque administrativo do master | **Isento** — o recolhimento (Spec 34) é operação interna, sem taxa |
| Receita | A taxa aparece como bucket próprio (`withdrawalFees`) no resumo financeiro do master |

## Contexto (por que agora)

O usuário pediu (2026-08-13) uma **taxa de saque de R$ 2,50** como novo
parâmetro de negócio. Hoje o saque é gratuito: o wallet é debitado apenas do
valor solicitado e a plataforma não tem receita por saque (o `master_ledger`
só registra `ADMIN_RECOVERY` para recolhimentos administrativos). A mudança
toca o caminho do dinheiro (débito, estorno, ledger do wallet, receita do
master) — por isso entra como spec com ciclo SDD completo.

## Critérios de aceite

1. **Configuração**: nova propriedade `foxcash.pricing.withdrawal-fee` (default
   **2.50**) em `PricingProperties`/`application.yml`, injetada no fluxo de
   saque (manual e automático) — nunca hardcoded no service.
2. **Débito total no create**: saque manual debita `valor + 2,50` do wallet
   (validação de saldo considera a taxa — saldo < valor + taxa → 409
   `insufficient_balance`). Ledger do wallet registra `WITHDRAWAL_DEBIT`
   (valor) **e** `WITHDRAWAL_FEE_DEBIT` (2,50) na mesma transação.
3. **Saque automático (Spec 70)**: o job saca `saldo - 2,50` (paga a taxa do
   próprio saldo; wallet zera). Saldo `<=` taxa → skip silencioso
   (`AutoWithdrawBelowThresholdException`).
4. **Saque administrativo isento**: `createAdministrativeWithdrawal` não cobra
   taxa (`fee = 0`), comportamento inalterado.
5. **Taxa persistida no saque**: nova coluna `fee_amount NUMERIC(15,2) NOT NULL
   DEFAULT 0.00` na tabela `withdrawals` (migration V51) — o valor da taxa faz
   parte do registro financeiro (reversão determinística, imune a mudança de
   config).
6. **Estorno devolve tudo**: saque FAILED credita `valor + taxa` ao saldo e
   registra `WITHDRAWAL_REVERSAL` (valor) + `WITHDRAWAL_FEE_REVERSAL` (taxa).
   Idempotente (gate `!= PROCESSING`, lock pessimista — padrão atual).
7. **Receita no master**: novo tipo `WITHDRAWAL_FEE` no `master_ledger`
   registrado no create (taxa); no FAILED, `WITHDRAWAL_FEE_REVERSAL` reverte
   (padrão SEC-29). `GET /master/revenue/summary` expõe `withdrawalFees`
   (aditivo): `withdrawalFees = Σ WITHDRAWAL_FEE − Σ WITHDRAWAL_FEE_REVERSAL`;
   `net` e `total` passam a incluir o bucket.
8. **Resposta do saque**: `WithdrawalResponse` expõe `fee` (aditivo) — o lojista
   e o master veem quanto foi cobrado.
9. **Testes**: unitários (Merchant/ProcessingPersister/Settlement/Service/
   Master/Revenue/Ledger) + E2E atualizados — journey 13/14/16/17, psp-flow,
   reliability e master-console-stepup (cenário de saque administrativo segue
   sem taxa).
10. **Documentação**: README (precificação) e `docs/politicas-uso.md` citam a
    taxa de saque.

## Dependências

- Specs **14/32/36** (saque manual, PROCESSING/COMPLETED/FAILED, estorno).
- Spec **70** (auto-withdraw — passa a pagar a taxa).
- Specs **27/34** (ledger do wallet e do master — novos tipos aditivos).
- Padrão **SEC-29** (`ADMIN_RECOVERY_REVERSAL`) para reversão de receita no FAILED.
- Migration: **V51** (última real V50).
