---
name: Adapter real de PIX de saída (Pagar.me/Asaas)
status: audited
references:
  - "management/vision.md#V30"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/38-payout-acquirer-ready/requirements.md"
---

# Spec 47 — Adapter real de PIX de saída (Pagar.me/Asaas)

## O que entrega

Implementa o **adapter concreto de PIX de saída** para o adquirente real
(ADR-005: **Pagar.me** primário, Asaas alternativa) — o passo que a Spec 38
deixou preparado ("quando o adquirente for definido: implementar o adapter
`@ConditionalOnProperty(payout-provider=<adquirente>)` + herdar o contrato +
preencher credenciais"). Conecta o saque do lojista (Spec 36) ao provedor
real de PIX de saída, com **zero mudança** em reconciliação/webhook/estorno.

- **Adapter `PagarMePayoutProviderClient`** implementando
  `PayoutProviderClient` (Spec 36/38):
  - `createPayout(PayoutCommand)` → `POST {base-url}/transfers` (Pagar.me v5 —
    transferência via PIX para a chave/destino), `code` = `withdrawal.id`
    (idempotente no adquirente), retorna `externalReference` (id da
    transferência).
  - `getPayout(externalReference)` → `GET {base-url}/transfers/{id}` →
    `PayoutStatus` canônico (**PAID** | **FAILED** | **PENDING**) — mapeia o
    vocabulário do adquirente (ex.: `processed`/`failed`/`pending`).
  - Timeouts 5s/10s (padrão do `PagarMePixProviderClient` — Spec 11) e
    exceções tipadas (`PixProviderTimeoutException`/`PixProviderException`
    ou equivalente da porta de payout).
- **Registro condicional:** `@ConditionalOnProperty(foxcash.psp.payout-provider
  = pagarme)` (config e fail-fast já prontos da Spec 38). Asaas fica como
  segundo adapter (mesmo harness), ativado por config.
- **Contrato garantido por teste:** herda o **`PayoutProviderContractTest`**
  (Spec 38) — o adapter real passa o mesmo harness do mock.
- **Runbook:** `deploy/payout-homologation.md` atualizado com o passo real
  (credenciais `NYX_PSP_PAYOUT_PAGARME_SECRET_KEY`, sandbox, validação de
  transferência + webhook de saída).

## Critérios de aceite

- `payout-provider=pagarme` + `NYX_PSP_PAYOUT_PAGARME_SECRET_KEY` → bean do
  adapter real ativo; `getPayout`/`createPayout` contra MockWebServer
  reproduzindo o contrato v5 (auth Basic, body, mapeamento de estados).
- Herda `PayoutProviderContractTest` e passa (garantia de contrato).
- Saque E2E com o adapter real (modo sandbox/MockWebServer): PROCESSING →
  PAID → COMPLETED via webhook de saída; FAILED → estorno; PENDING não
  liquida (Spec 38).
- Sem mudança em `WithdrawalService`/`WithdrawalSettlementService`/
  `WithdrawalReconciliationService` (a porta isola o adapter).
- `mvn test` verde e E2E 22/22 sem regressão (mock continua default em dev).

## Dependências

- `management/specs/36-payout-psp/requirements.md` (porta/contrato)
- `management/specs/38-payout-acquirer-ready/requirements.md` (config,
  PENDING, runbook, harness de contrato)
- `management/adr.md#ADR-005` (escolha do adquirente)
