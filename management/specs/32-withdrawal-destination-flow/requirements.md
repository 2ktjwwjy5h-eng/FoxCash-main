---
name: Saque com destino selecionado + bloqueio lacrado com saque administrativo do master
status: audited
references:
  - "management/vision.md#V19"
  - "management/specs/31-withdrawal-destinations/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/26-qa-hardening-financeiro/requirements.md"
---

# Spec 32 — Saque com destino + saque administrativo do master

## O que entrega

Dois requisitos do usuário (decisões 2026-08-04):

1. **Saque com destino selecionado:** o saque deixa de ser `{amount}` solto e
   passa a exigir o **destino** cadastrado pelo vendedor na conta dele (Spec
   31). O fluxo continua **instantâneo** (debita e `COMPLETED` na mesma
   transação — decisão do usuário), mas o dinheiro só sai **para o destino que
   o vendedor escolheu**. Nada de chave fixa automática.
2. **Bloqueio "lacrado" + saque administrativo do master:** quando o master
   bloqueia os saques (Spec 14), o bloqueio é **total para o vendedor** (409
   `withdrawals_blocked`, sem exceção), mas o **master pode sacar em nome do
   lojista** — para recolher valores (taxas, chargebacks, encerramento) —
   **apenas até o saldo disponível** (resultado nunca negativo).

Também reforça o requisito de **evitar saques duplos/múltiplos**: o lock
pessimista existente (Spec 26) já serializa saques concorrentes do mesmo
lojista; com o destino obrigatório, a validação e o débito acontecem sob o
mesmo lock, e o teste de concorrência passa a cobrir o fluxo completo.

1. **`POST /api/v1/merchants/{id}/withdrawals`** passa a exigir
   `{amount, destinationId}`: valida que o destino pertence ao lojista e está
   ativo (404 `destination_not_found` / 409 `destination_inactive`), debita sob
   lock e grava `COMPLETED` com o destino.
2. **Migration V21:** `withdrawals` ganha `destination_id`, `type`
   (`MERCHANT` | `ADMINISTRATIVE`), `reason` e **snapshot do destino**
   (criptografado) — o saque preserva o endereço usado mesmo se o destino for
   editado/inativado depois (auditoria).
3. **Saque administrativo:** `POST
   /api/v1/master/merchants/{id}/withdrawals/administrative`
   `{amount, destinationKey, reason}` → debita **mesmo com bloqueio ativo ou
   lojista não-ACTIVE**, **nunca deixando saldo negativo** (409
   `insufficient_balance` se `amount > saldo`); grava `type=ADMINISTRATIVE` +
   motivo. O destino é informado pelo master no ato (não consulta a tabela
   privada do lojista — privacidade da Spec 31).
4. **Ledger (Spec 27):** todo saque (vendedor ou administrativo) grava a entry
   `WITHDRAWAL_DEBIT` com referência do saque — auditável e reconciliável.

## Critérios de aceite

1. `POST /withdrawals` sem `destinationId` → **400 `invalid_argument`**;
   destino de outro lojista → **404 `destination_not_found`** (não revela
   existência); destino inativo → **409 `destination_inactive`**.
2. Saque válido → **201** com `{withdrawalId, amount, status: COMPLETED,
   walletBalance}`; `withdrawals.destination_id` + snapshot preenchidos;
   débito + 1 entry `WITHDRAWAL_DEBIT` no ledger (Spec 27).
3. Bloqueio master ativo → vendedor recebe **409 `withdrawals_blocked`**
   (lacrado — nenhuma rota do vendedor contorna).
4. `POST /master/merchants/{id}/withdrawals/administrative` com bloqueio ativo
   e/ou lojista não-ACTIVE → **201** (master saca em nome do lojista); com
   `amount > saldo` → **409 `insufficient_balance`** (saldo nunca negativo);
   sem `reason` → **400**.
5. Saque administrativo registra `type=ADMINISTRATIVE`, `reason` e entry
   `WITHDRAWAL_DEBIT`; visível em `GET /api/v1/master/withdrawals` com o tipo.
6. Saques concorrentes do mesmo lojista (2× ao mesmo tempo) → exatamente um
   passa com o saldo; o segundo recebe `insufficient_balance` (sem overdraft —
   lock pessimista).
7. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 12:
   saque com destino, bloqueio lacrado, saque administrativo, 400/404/409).

## Dependências

- Spec 31 (destinos de saque — `destinationId` obrigatório).
- Spec 27 (ledger — `WITHDRAWAL_DEBIT` para vendedor e administrativo).
- Spec 14 (fluxo de saques e controle de bloqueio existente).
- Spec 26 (lock pessimista `findByIdForUpdate` — serializa saques).

## Observações

- **Fluxo permanece instantâneo** (decisão do usuário) — sem reserva nem
  processamento; a transferência real via PSP (Pix de saída) fica para spec
  futura (hoje debita e `COMPLETED`).
- **Bloqueio lacrado:** `Merchant.withdraw` (vendedor) valida
  ACTIVE+desbloqueio+saldo; `Merchant.adminWithdraw` (master) valida **apenas**
  saldo ≥ valor — o master pode recolher de lojista bloqueado/suspenso.
- **Privacidade (Spec 31):** o endpoint administrativo **não** acessa os
  destinos privados do lojista — o master informa a chave de destino no
  request (responsabilidade dele).
- **Anti-saque-duplo:** o lock pessimista + validação sob lock é a garantia; o
  teste de concorrência (critério 6) documenta o comportamento.
