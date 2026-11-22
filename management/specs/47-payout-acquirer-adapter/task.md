# Spec 47 — Task breakdown

Status: `audited` (2026-08-06). Fim do ciclo SDD — ver `/spec-audit` abaixo.

## Implementação

- [x] `PagarMePayoutProviderClient` (infrastructure/outbound/pix):
      `@ConditionalOnProperty(payout-provider=pagarme)`, auth Basic (secret key),
      timeouts 5s/10s, `createPayout` (POST /transfers, `code` = withdrawal.id,
      `pix: {pix_key, pix_key_type}` inferido do formato canônico — CPF 11 dígitos /
      CNPJ 14 / fone `+55` / EVP UUID; `BANK_ACCOUNT`/TED → erro explícito) e
      `getPayout` (GET /transfers/{id} → processed=PAID, failed/canceled=FAILED,
      demais=PENDING). Exceções tipadas na porta (`PayoutProviderException`/
      `PayoutProviderTimeoutException`).
- [x] Records v5 de request/response (TransferRequest/PixRequest/TransferResponse).
- [x] `PagarMePayoutProviderClientTest` — herda `PayoutProviderContractTest` (3
      invariantes) + MockWebServer: auth Basic, body (code/amount/pix), 200 com id,
      4xx → exceção, estados, BANK_ACCOUNT, inferência do tipo, centavos em long.
- [ ] (opcional, se adquirente secundário definido) `AsaasPayoutProviderClient`
      + teste no mesmo harness. — **adiado**: Pagar.me é o adquirente primário
      (ADR-005); Asaas entra no mesmo harness quando definido.
- [x] `deploy/payout-homologation.md` — passo real marcado (adapter Pagar.me
      implementado; falta só sandbox com credenciais reais).
- [x] README (config `NYX_PSP_PAYOUT_PROVIDER=pagarme`).

## Verificação

- [x] `mvn test` verde (**740/0/0** — +12 do adapter real; harness herdado passa).
- [x] E2E **27/27** sem regressão (mock segue default em dev).
- [x] Smoke ao vivo (dev com `payout-provider=pagarme`): boot OK com o bean real;
      saque com secret fake → chamada real ao Pagar.me → 401 → estorno
      (FAILED + `WITHDRAWAL_REVERSAL` no ledger, saldo restaurado).
- [ ] Smoke em sandbox (se credenciais disponíveis): saque real → PROCESSING →
      webhook PAID → COMPLETED; FAILED → estorno. — **pendente** (requer
      credenciais de sandbox do usuário).

## Auditoria (`/spec-audit`, 2026-08-06)

**5/5 critérios de aceite OK — 0 achados que exijam correção.**

1. [x] `payout-provider=pagarme` + `NYX_PSP_PAYOUT_PAGARME_SECRET_KEY` → bean do
      adapter real ativo (`@ConditionalOnProperty`); `createPayout`/`getPayout`
      contra MockWebServer reproduzindo o contrato v5 (auth Basic, body
      code/amount/pix, mapeamento de estados) — verificado no código e nos testes.
2. [x] Herda `PayoutProviderContractTest` e passa (3 invariantes + 9 específicos =
      **12/12** em `PagarMePayoutProviderClientTest`).
3. [x] Fluxo de saque com o adapter real: FAILED → estorno automático
      (`WITHDRAWAL_REVERSAL`, saldo restaurado) — smoke ao vivo documentado; PENDING
      não liquida (Spec 38) — coberto no mapeamento de estados.
4. [x] Sem mudança no núcleo — commit `d4fde7f` tocou apenas README, runbook,
      roadmap, requirements/task, porta (exceções tipadas) e o adapter + teste;
      `WithdrawalService`/`WithdrawalSettlementService`/`WithdrawalReconciliationService`
      intactos.
5. [x] `mvn test` **740/0/0** BUILD SUCCESS (re-executado na auditoria) e E2E
      **27/27** (re-executado ao vivo, mock segue default em dev).

Verificações adicionais: `pix_key_type` inferido do formato canônico (CPF 11 /
CNPJ 14 / `+55...` phone / UUID evp — sem colisão com o regex da Spec 31);
`BANK_ACCOUNT`/TED → erro explícito (documentado, exigiria `document_number`);
timeouts 5s/10s; centavos em `long` sem estouro (B1); chave Pix nunca em claro
nos logs (mask); fail-fast `PspProperties` (secret-key do Pagar.me obrigatória
com `payout-provider=pagarme`, testado em `PspPropertiesTest`);
`application-prod.yml` sem default para `NYX_PSP_PAYOUT_PROVIDER`. Runbook
`deploy/payout-homologation.md` e README atualizados. Única pendência: passo 7 do
runbook (homologação em sandbox com credenciais reais do usuário) — fora do
escopo da auditoria de código.
