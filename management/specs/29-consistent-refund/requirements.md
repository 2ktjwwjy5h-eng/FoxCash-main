---
name: Estorno consistente — fonte única de débito e webhook tolerante a ordem
status: audited
references:
  - "management/vision.md#V18"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/26-qa-hardening-financeiro/requirements.md"
---

# Spec 29 — Estorno consistente

## O que entrega

Dois problemas no fluxo de estorno (diagnóstico Fase 5):

- **C1 — Duplo débito:** `RegisterChargebackService` debita o wallet mas **não
  muda o status da transação** (continua `APPROVED`). Quando o PSP processa a
  devolução e envia o webhook `REFUNDED`, o `ProcessPixWebhookService` vê
  `APPROVED → REFUNDED` (transição válida) e **debita de novo**. O mesmo evento
  financeiro gera **2 débitos**.
- **M1 — Fora de ordem:** um webhook com estado inesperado para a transação
  (ex.: `REFUNDED` de `PENDING`, `APPROVED` de `REFUNDED`) lança
  `IllegalStateException` → **409 `invalid_state`** para o PSP. Na prática o PSP
  pode interpretar como erro permanente e **parar de reenviar**, deixando a
  transação presa. Um webhook nunca deve responder erro de estado para o
  provedor.

Esta spec garante **fonte única de débito de estorno** e um webhook
**tolerante a ordem**:

1. **Chargeback marca a transação `REFUNDED`** (transição `refund()` já
   validada: só de `APPROVED`) + grava `CHARGEBACK_DEBIT` no ledger (Spec 27).
   O webhook `REFUNDED` posterior vira **no-op** — o débito acontece **uma
   única vez**. Bônus: a venda estornada deixa de aparecer como "concluída" no
   portal (Spec 25 filtra por `APPROVED`) — corrige o **M4**.
2. **Chargeback duplicado → 409 `chargeback_already_exists`** (antes: cheque
   não serializado + unique violation → **500**) — corrige o **M3**. Uso de
   lock pessimista na transação (`findByIdForUpdate`) + catch da constraint
   como backstop.
3. **Webhook tolerante a ordem:** o `ProcessPixWebhookService` não lança mais
   por estado inesperado — cada status só é aplicado se a transição for válida
   a partir do estado atual; caso contrário **204 no-op com log de alerta**
   (evento registrado para auditoria/reconciliação). Efeito financeiro **nunca**
   duplicado.
4. **Status desconhecido** continua `400 invalid_argument` (contrato, sem
   efeito financeiro).

## Critérios de aceite

1. Chargeback registrado em transação `APPROVED` → a transação vira `REFUNDED`
   (persistida), o wallet é debitado 1× e há **1 entry `CHARGEBACK_DEBIT`** no
   ledger.
2. Webhook `REFUNDED` chegando **depois** do chargeback → **204 no-op**, sem
   segundo débito (saldo não muda).
3. Webhook `REFUNDED` chegando **antes** de qualquer chargeback (`APPROVED` →
   `REFUNDED`) → 204, wallet debitado 1× com **1 entry `REFUND_DEBIT`**; um
   chargeback posterior → **409 `invalid_state`** (transação não está mais
   `APPROVED`).
4. Webhook fora de ordem (`REFUNDED` de `PENDING`, `APPROVED` de `REFUNDED`,
   `PENDING` de `APPROVED`) → **204 no-op** (nunca 409/500 para o PSP), com log
   de alerta.
5. Chargeback duplicado (mesma transação): concorrente ou sequencial → **409
   `chargeback_already_exists`** (nunca 500), sem segundo débito.
6. Replay de `REFUNDED` (transação já `REFUNDED`) → 204 no-op, sem débito.
7. Status desconhecido no webhook → `400 invalid_argument`, sem efeito
   financeiro.
8. `mvn test` verde (204 + novos) e E2E sem regressão (**10/10** + cenário de
   chargeback → REFUNDED e webhook pós-chargeback).

## Dependências

- Spec 27 (ledger — `CHARGEBACK_DEBIT`/`REFUND_DEBIT` idempotentes).
- Spec 19 (registro de chargeback — base do fluxo).
- Spec 26 (locks pessimistas; estorno debita saldo).
- Spec 18 (webhook idempotente — replay no-op).

## Observações

- **Sem nova migration** — mudança de comportamento nos serviços; o ledger
  (Spec 27) e as tabelas existentes (`chargebacks`, `pix_transactions`) já
  cobrem o modelo.
- **Decisão de produto mantida:** saldo pode ficar negativo (Spec 26). Esta
  spec apenas garante que o débito acontece **uma vez**.
- **`REFUNDED` de `PENDING` (fora de ordem):** no-op + alerta. A recuperação
  desse caso (ex.: webhook atrasado com transação ainda pendente) é coberta
  pela reconciliação da Spec 30.
- **Ordem de locks consistente (L1):** o chargeback passa a travar a transação
  (`findByIdForUpdate`) e depois o merchant — mesma ordem do webhook
  (tx → merchant). Regra documentada no design.
