---
name: PIX de saída pronto para o adquirente real (payout plug-and-play)
status: audited
references:
  - "management/vision.md#V21"
  - "management/specs/36-payout-psp/requirements.md"
  - "management/specs/10-psp-integration-foundation/requirements.md"
  - "management/specs/11-pagarme-adapter/requirements.md"
  - "management/adr.md#adr-005"
---

# Spec 38 — PIX de saída pronto para o adquirente real

## O que entrega

A Spec 36 entregou o **contrato de PIX de saída** (porta `PayoutProviderClient` +
mock + webhook `/webhooks/payouts` + reconciliação) com um *slot* para o
adquirente real que "o usuário escolherá depois" (ADR-005: Pagar.me primário /
Asaas alternativa). Esta spec **deixa tudo preparado** para plugar o adquirente:
quando o usuário o escolher, o trabalho é só **implementar o adapter** e
**preencher as credenciais** — sem mexer em reconciliação, webhook ou estorno.

Três lacunas concretas hoje impedem o "plug-and-play":

1. **Reconciliação trata qualquer estado ≠ PAID como FAILED** (robustez para
   adquirentes reais). `WithdrawalReconciliationService` faz
   `getPayout() == PAID ? "PAID" : "FAILED"` — se o PSP real responder "em
   processamento" (estado intermediário), o saque seria marcado `FAILED` e o
   débito **estornado indevidamente**. Adquirentes reais têm estados
   intermediários (ex.: Pagar.me payout `processing`/`pending`).
2. **Sem configuração por adquirente e sem fail-fast de produção.**
   `application-prod.yml` tem `payout-provider: ${NYX_PSP_PAYOUT_PROVIDER:mock}`
   — o **mock é default em produção** (risco: operar com mock sem perceber).
   Não há blocos `payout-pagarme`/`payout-asaas` (secret-key/base-url) nem
   validação fail-fast quando o provider for escolhido.
3. **Sem teste de contrato do adapter nem runbook de homologação do payout.**
   Não há garantia automática de que um adapter real cumpre o contrato da
   porta, e não há documentação de como plugá-lo (o `deploy/psp-homologation.md`
   cobre apenas a cobrança — Spec 10/11).

## Critérios de aceite

1. **`PayoutProviderClient.PayoutStatus` ganha `PENDING`** (estado
   intermediário de adquirentes reais); a reconciliação **não liquida** saque
   `PROCESSING` quando o PSP responde `PENDING` (sai sem `settleByExternalReference`,
   permanece `PROCESSING` para o próximo run); `PAID`/`FAILED` continuam como
   hoje (teste no `WithdrawalReconciliationServiceTest`).
2. **`foxcash.psp.payout-provider` sem default em produção** (`application-prod.yml`
   exige `NYX_PSP_PAYOUT_PROVIDER`; boot falha se ausente) — mock só em dev.
3. **Config por adquirente** (`PspProperties`): blocos `payoutPagarme`/
   `payoutAsaas` (`secret-key`, `base-url`) mapeados de
   `NYX_PSP_PAYOUT_PAGARME_SECRET_KEY`/`NYX_PSP_PAYOUT_PAGARME_BASE_URL` (e
   análogos para asaas); `validate()` fail-fast: `payout-provider=pagarme` sem
   secret-key → boot falha (mesmo padrão da cobrança, Spec 10/11).
4. **`PayoutProviderContractTest`** (teste de contrato abstrato): o
   `MockPayoutProviderClient` herda e passa (externalReference não-vazia no
   aceite; `getPayout` em `PAID`/`FAILED`/`PENDING`; falha síncrona lança). O
   adapter real deve **herdar** este teste — contrato verificado
   automaticamente quando for criado.
5. **`deploy/payout-homologation.md`** (runbook): contrato da porta
   (`PayoutCommand` → `PayoutResult`, `getPayout`), mapeamento de estados,
   webhook de saída (payload + assinatura), configuração por adquirente e o
   **checklist** para plugar o adquirente (implementar adapter
   `@ConditionalOnProperty(payout-provider=...)` + herdar o teste de contrato +
   credenciais + validação em sandbox).
6. **README**: seção de PIX de saída atualizada (como selecionar o adquirente e
   onde está o runbook).
7. `mvn test` verde (373 + novos) e E2E **sem regressão**.

## Dependências

- Spec 36 (porta `PayoutProviderClient`, mock, webhook de saída, reconciliação).
- Spec 10/11 (padrão de adapter real + fail-fast + runbook de homologação).
- ADR-005 (shortlist do PSP: Pagar.me primário / Asaas alternativa).

## Observações

- **Sem mudança de contrato HTTP:** o webhook `/webhooks/payouts` e o payload
  `{externalReference, status, failedReason}` permanecem; o adapter real mapeia
  o vocabulário do PSP para o vocabulário canônico (`PAID`/`FAILED` e, na
  consulta de reconciliação, também `PENDING`).
- **O adapter real NÃO é escrito aqui** (depende de credenciais/contrato do
  adquirente escolhido). Esta spec prepara a estrutura, a robustez, o teste de
  contrato e a documentação — o adapter entra numa spec futura quando o
  adquirente for definido (ou diretamente seguindo o runbook).
- **`getPayout` não carrega motivo de falha** (enum simples) — o motivo real
  chega pelo webhook `FAILED` (`failedReason`); o adapter pode logar o motivo
  da consulta. Decisão de simplicidade (KISS).
- **Mock em prod:** além do fail-fast (sem default), o `PayoutProviderClient`
  mock continua `@ConditionalOnProperty(payout-provider=mock)` — selecionar
  `mock` em prod exige a env var explícita (consciente).
