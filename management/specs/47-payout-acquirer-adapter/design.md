# Spec 47 — Design: Adapter real de PIX de saída (Pagar.me/Asaas)

## Contexto

A Spec 36 definiu a porta `PayoutProviderClient` + mock; a Spec 38 preparou
config por adquirente (fail-fast), o estado `PENDING` na reconciliação, o
harness `PayoutProviderContractTest` e o runbook. Este design detalha o
adapter **Pagar.me v5** (ADR-005) e deixa o caminho para o Asaas.

## Decisões de arquitetura

### 1. `PagarMePayoutProviderClient`

- Registrado com `@ConditionalOnProperty(prefix = "foxcash.psp", name =
  "payout-provider", havingValue = "pagarme")` — mesma estratégia do
  `PagarMePixProviderClient` (Spec 11).
- **`createPayout`** → `POST {base-url}/transfers`:
  - Body (v5): `{code: withdrawal.id, amount: <centavos long>, pix: {
    pix_key: destination.value(), pix_key_type: <cpf|cnpj|phone|evp|bank> }}`
    (o `PayoutDestination` da porta já carrega value/bankCode/agency/account).
  - Resposta: `{id}` → `externalReference = id` (usado no webhook de saída e
    na reconciliação — Spec 36).
  - `code` = `withdrawal.id` → idempotente no adquirente (re-entrega do saque
    com o mesmo id não duplica transferência — padrão Spec 28).
- **`getPayout`** → `GET {base-url}/transfers/{id}` → mapeia para
  `PayoutStatus`: `status == "processed"` → `PAID`; `"failed"`/`"canceled"` →
  `FAILED`; demais (`"pending"`/`"in_process"`) → `PENDING` (Spec 38: PENDING
  não liquida — o saque PROCESSING permanece para o próximo run).
- **Erros/timeouts:** `ResourceAccessException` → `PixProviderTimeoutException`
  (saque fica PROCESSING sem referência — retry idempotente da Spec 42/B-04);
  `RestClientException` → `PixProviderException`.
- Auth: HTTP Basic `base64(secretKey:)` (padrão Spec 11); `secretKey` de
  `foxcash.psp.payout-pagarme.secret-key` (fail-fast da Spec 38).

### 2. Harness de contrato

- `PagarMePayoutProviderClientTest` **herda** `PayoutProviderContractTest`
  (Spec 38) + testes específicos com MockWebServer (auth Basic, body do
  transfer, 200 com id, 4xx → exceção, estados PENDING/PAID/FAILED).

### 3. Asaas (segundo adapter)

- Mesmo padrão: `AsaasPayoutProviderClient` com
  `@ConditionalOnProperty(payout-provider = asaas)` + `NYX_PSP_PAYOUT_ASAAS_SECRET_KEY`
  (config já existe). Contrato da API Asaas v3 (`POST /pix/cashierPayments`? —
  validar no momento da implementação) mapeado para a mesma porta + harness.
  Fora do escopo imediato se o adquirente escolhido for só o Pagar.me.

### 4. Sem mudança no núcleo

- `WithdrawalService`/`MasterWithdrawalService` (Spec 42/B-04 — PSP fora da
  tx), `WithdrawalSettlementService` e `WithdrawalReconciliationService` já
  dependem apenas da porta — o adapter real pluga sem tocar no fluxo.

## Arquivos (visão geral)

- `infrastructure/outbound/pix/PagarMePayoutProviderClient` (+ records de
  request/response v5)
- `src/test/java/.../PagarMePayoutProviderClientTest` (harness + MockWebServer)
- (opcional) `AsaasPayoutProviderClient` + teste
- `deploy/payout-homologation.md` (passo do adquirente real marcado)
- README (config `NYX_PSP_PAYOUT_PROVIDER=pagarme`)
