---
name: Saque via PSP — Pix de saída com contrato de provider, webhook e reconciliação (adquirente real entra depois)
status: audited
references:
  - "management/vision.md#V20"
  - "management/specs/32-withdrawal-destination-flow/requirements.md"
  - "management/specs/10-psp-integration-foundation/requirements.md"
  - "management/specs/11-pagarme-adapter/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/30-wallet-reconciliation/requirements.md"
---

# Spec 36 — Saque via PSP (Pix de saída)

## O que entrega

Hoje o saque (Spec 32) debita o saldo e marca `COMPLETED` — **o dinheiro não vai
a lugar nenhum**: não existe saída real (Pix de saída/TED) via PSP. O usuário
confirmou (2026-08-04): o **adquirente real entra depois** — esta spec prepara
todo o **contrato e a estrutura** para o saque via PSP, no mesmo padrão das
Specs 10/11 (porta + adapter mock + adapter real plugável via configuração).

Esta spec entrega:

1. **Porta `PayoutProviderClient`** (saída): `createPayout(amount, destination,
   description)` → `PayoutResult(externalReference)` — espelha o
   `PixProviderClient` da Spec 10/11. Adapter **mock** default (dev) e adapter
   real plugável por configuração (`foxcash.psp.payout-provider`), deixando o
   slot pronto para Pagar.me/Asaas/qualquer adquirente.
2. **Saque com estado real:** `Withdrawal` ganha a máquina de estados
   `PROCESSING → COMPLETED | FAILED`. O **débito continua imediato** (o valor sai
   do saldo na solicitação — decisão do usuário mantida, sem reserva separada);
   o PSP processa em seguida; o webhook de saída confirma `COMPLETED` ou
   `FAILED`. Em `FAILED`, o valor é **estornado automaticamente** para o saldo
   (ledger `WITHDRAWAL_REVERSAL`).
3. **Webhook de saída do PSP:** `POST /webhooks/payouts` (assinatura HMAC no
   padrão da Spec 11, `WebhookSignatureFilter` reutilizado) atualiza o saque e,
   se preciso, estorna o débito — tudo **idempotente** (eventId) e sob lock.
4. **Reconciliação de saques pendentes:** `WithdrawalReconciliationService`
   consulta o PSP (GET payouts) e encerra saques `PROCESSING` órfãos — mesmo
   papel da Spec 30 para o lado de saída.
5. **Snapshot completo do destino** no saque (chave + dados bancários mascarados
   no histórico), já iniciado na Spec 32, agora suficiente para o PSP processar
   sem depender da edição futura do destino.

## Critérios de aceite

1. Migration V25: `withdrawals` ganha `status` (`PROCESSING`/`COMPLETED`/
   `FAILED`), `external_reference`, `payout_provider`, `failed_at`/`completed_at`
   + índice `(status, created_at)` para reconciliação; `payouts` (opcional,
   histórico de tentativas) se necessário.
2. `PayoutProviderClient` + `MockPayoutProviderClient` (default, responde aceite
   e permite simular falha via configuração) + adapter real plugável por
   `foxcash.psp.payout-provider` — falha de config com provider desconhecido →
   fail-fast.
3. Saque do vendedor e administrativo (Spec 32) debitam e gravam `PROCESSING`,
   chamam o provider (na mesma transação ou via outbox com retry — ver design),
   registram `external_reference`.
4. Webhook de saída: `PAID` → `COMPLETED` (idempotente); `FAILED` → estorno do
   débito (`WITHDRAWAL_REVERSAL` no ledger) + `FAILED` com motivo; assinatura
   HMAC validada; rejeita com 401 se inválida.
5. Reconciliação: job (ShedLock `foxcash-payout-reconciliation`) consulta o PSP
   e encerra `PROCESSING` órfãos; sem duplicar estorno (gate de status).
6. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário 16:
   saque com PSP mock → PROCESSING → COMPLETED via webhook; e cenário 17: falha
   → estorno automático).

## Dependências

- Spec 32 (saque com destino — base; evolui o estado para PROCESSING/FAILED).
- Spec 10/11 (padrão da porta + webhook assinado + properties do PSP).
- Spec 27 (ledger — `WITHDRAWAL_DEBIT` na solicitação, `WITHDRAWAL_REVERSAL` no
  estorno).
- Spec 31 (destino — fonte dos dados do payout).

## Observações

- **Adquirente real fica para depois** (decisão do usuário): o contrato já está
  pronto — implementar Pagar.me/Asaas de saída é só um adapter novo + properties.
- **Débito imediato mantido:** não há reserva separada (fica para a spec de
  reserva de valor). O `FAILED` devolve o dinheiro ao saldo.
- **Webhook de saída** usa o mesmo `WebhookSignatureFilter` da Spec 11
  (roteamento por path + segredo configurável).
