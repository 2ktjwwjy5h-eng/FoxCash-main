# Spec 38 — Task breakdown

Status: `audited` (2026-08-05). Checklist concluído.

## Implementação

- [x] `PayoutProviderClient`: enum `PayoutStatus` ganha `PENDING` + Javadoc do
      contrato da porta (mapeamento de estados do PSP → PAID/FAILED/PENDING).
- [x] `WithdrawalReconciliationService.reconcile`: `switch (status)` — `PAID` →
      liquida, `FAILED` → liquida com motivo, `PENDING` → **skip** (saque
      PROCESSING permanece; próximo run re-consulta).
- [x] `PspProperties`: blocos `payoutPagarme`/`payoutAsaas` (`secretKey`/`baseUrl`)
      + getters/setters; `validate()` fail-fast (provider=pagarme/asaas sem
      secret-key → boot falha, mesmo padrão da cobrança).
- [x] `application-prod.yml`: `payout-provider: ${NYX_PSP_PAYOUT_PROVIDER}`
      (SEM default — fail-fast) + blocos `payout-pagarme`/`payout-asaas` com
      env vars sem default (base-url com default do adquirente).
- [x] `application.yml` (dev): mantém `payout-provider: ${NYX_PSP_PAYOUT_PROVIDER:mock}`
      + blocos `payout-pagarme`/`payout-asaas` com defaults fake.
- [x] `PayoutProviderContractTest` (harness abstrato): aceite devolve
      externalReference não-vazia; `getPayout` devolve estado canônico;
      falha síncrona lança (configurável pelo adapter).
- [x] `MockPayoutProviderClientTest` estende o contrato (além dos testes de
      mock-payout-failure).
- [x] `deploy/payout-homologation.md` (runbook): contrato da porta, mapeamento
      de estados, webhook de saída, config por adquirente e checklist para
      plugar o adquirente.
- [x] README: seção de PIX de saída atualizada (seleção do adquirente +
      link para o runbook).

## Verificação

- [x] `WithdrawalReconciliationServiceTest`: novo caso `PENDING` → não chama
      `settleByExternalReference` e o saque permanece PROCESSING (contagem de
      liquidados não incrementa).
- [x] `PayoutProviderContractTest` herdado pelo mock passa (3 invariantes).
- [x] Boot `prod` sem `NYX_PSP_PAYOUT_PROVIDER` falha (fail-fast).
- [x] Boot com `payout-provider=pagarme` sem `NYX_PSP_PAYOUT_PAGARME_SECRET_KEY`
      falha (fail-fast, validado no `PspProperties.validate()`).
- [x] `mvn test` verde (373 + novos) e E2E sem regressão (mock continua default
      em dev).
- [x] Roadmap `planned` → `audited`; requirements/design/task atualizados com
      contagens reais.
- [x] `/spec-audit` → `audited` (fim do ciclo SDD).
