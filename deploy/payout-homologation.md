# Homologação do PIX de saída (Spec 36/38) — runbook do adquirente real

Este documento descreve o **contrato de integração** do PIX de saída (payout) e o
**checklist para plugar o adquirente real** quando ele for escolhido (Pagar.me
primário / Asaas alternativa — ver ADR-005). A Spec 38 deixou **tudo preparado**:
o trabalho quando o adquirente for definido é só **implementar o adapter** e
**preencher as credenciais**.

> **Estado atual:** a Spec 36 entregou a porta `PayoutProviderClient` + o
> `MockPayoutProviderClient` (dev) + webhook `/webhooks/payouts` + reconciliação.
> A Spec 38 preparou o slot: estado `PENDING` na reconciliação, config por
> adquirente com fail-fast, teste de contrato do adapter e este runbook.
> **A Spec 47 implementou o adapter real Pagar.me (v5)** — `PagarMePayoutProviderClient`
> (POST/GET `/transfers`, auth Basic, timeouts 5s/10s, exceções tipadas,
> `pix_key_type` inferido do formato canônico da chave; `BANK_ACCOUNT`/TED →
> erro explícito nesta versão) com o harness de contrato herdado. **Falta só
> preencher as credenciais de sandbox e homologar** (checklist abaixo).

---

## 1. Contrato da porta (`PayoutProviderClient`)

```java
PayoutResult createPayout(PayoutCommand command);       // solicita o PIX de saída
PayoutStatus getPayout(String externalReference);       // consulta (reconciliação)
```

- **`PayoutCommand`** — `withdrawalId` (id do saque — referência interna enviada
  ao PSP), `amount`, `destination` (chave Pix `PIX_*` ou dados bancários
  `BANK_ACCOUNT` — já **desencriptados** pelo serviço), `description`.
- **`PayoutResult`** — `externalReference` (referência do PSP, usada no webhook
  de saída e na reconciliação). **Nunca vazia.**
- **`getPayout`** — devolve o estado **canônico**:
  | Canônico | Significado | Ação da reconciliação |
  |---|---|---|
  | `PAID` | confirmado | liquida → `COMPLETED` |
  | `FAILED` | rejeitado | liquida → `FAILED` + **estorno automático** do débito |
  | `PENDING` | ainda em processamento | **não liquida** — saque permanece `PROCESSING`; o próximo run re-consulta |
- **Falha síncrona** (timeout/4xx/5xx no `createPayout`) → lançar exceção: o
  fluxo de saque faz **rollback** (nada é criado nem debitado).

O adapter mapeia o vocabulário do PSP para o canônico (ex.: Pagar.me
`processing`/`pending` → `PENDING`; Asaas `pending` → `PENDING`).

## 2. Webhook de saída (`POST /webhooks/payouts`)

- **Payload canônico:** `{ "externalReference": "...", "status": "PAID"|"FAILED",
  "eventId": "...", "failedReason": "..." }` — o adapter converte no envio; o
  webhook já chega no vocabulário canônico.
- **Assinatura:** `X-Psp-Signature` = `HMAC-SHA256(segredo, rawBody)` em hex
  minúsculo — segredo `foxcash.psp.payout-webhook.secret`
  (`FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET` em prod, **obrigatório** sem default).
- **Comportamento:** sem assinatura/inválida → `401`; válida → processa → `204`.
  `PAID` → saque `COMPLETED`; `FAILED` → `FAILED` + estorno (`WITHDRAWAL_REVERSAL`).
  Re-entrega → no-op (gate de status, idempotente).
- **`PENDING` NÃO existe no webhook** (o webhook já carrega o desfecho); só a
  consulta `getPayout` devolve `PENDING`.

## 3. Configuração por adquirente (env vars)

| Variável | Obrigatória | Descrição |
|---|---|---|
| `FOXCASH_PSP_PAYOUT_PROVIDER` | **sim (prod, sem default)** | `mock` (dev) / `pagarme` / `asaas` |
| `FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET` | sim (prod) | segredo da assinatura do webhook de saída |
| `FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY` | quando `payout-provider=pagarme` | secret key da API de saída do Pagar.me |
| `FOXCASH_PSP_PAYOUT_PAGARME_BASE_URL` | não | default `https://api.pagar.me/core/v5` |
| `FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY` | quando `payout-provider=asaas` | secret key da API de saída do Asaas |
| `FOXCASH_PSP_PAYOUT_ASAAS_BASE_URL` | não | default `https://www.asaas.com/api/v3` |

**Fail-fast (Spec 38):** em produção `FOXCASH_PSP_PAYOUT_PROVIDER` é obrigatório
(mock nunca em prod por acidente — boot falha se ausente); escolher
`pagarme`/`asaas` sem a secret-key correspondente também falha o boot. Escolher
um provider **sem adapter concreto** falha com bean `PayoutProviderClient`
ausente (é o comportamento esperado até o adapter ser implementado).

## 4. Checklist para plugar o adquirente (Spec 38 — "só identificar")

> **Status (Spec 47, 2026-08-06):** os passos 1–5 estão **feitos para o Pagar.me**
> (adapter `PagarMePayoutProviderClient` + harness herdado em
> `PagarMePayoutProviderClientTest`). Falta o **passo 7** (homologação em
> sandbox com credenciais reais) e, se o Asaas for escolhido, o **passo 4** no
> mesmo harness.

1. **Escolher o adquirente** (ADR-005) e definir `FOXCASH_PSP_PAYOUT_PROVIDER`.
   → **Pagar.me escolhido (primário)** — `FOXCASH_PSP_PAYOUT_PROVIDER=pagarme`.
2. **Criar a conta sandbox** do adquirente e gerar as credenciais de API.
   → pendente (credenciais de sandbox do usuário).
3. **Documentar o contrato HTTP do adquirente** (endpoint de payout, formato do
   request/response, estados possíveis, formato do webhook e da assinatura).
   → **feito no adapter**: `POST/GET {base-url}/transfers` (v5); request
   `{code: withdrawal.id, amount: <centavos long>, pix: {pix_key, pix_key_type}}`;
   response `{id, status}`; estados `processed`→PAID, `failed`/`canceled`→FAILED,
   demais→PENDING. **Importante:** o Pagar.me v5 NÃO tem webhook próprio de
   transferência com o formato canônico — o desfecho chega via `getPayout`
   (reconciliação) ou pela conversão manual para o `POST /webhooks/payouts`.
4. **Implementar o adapter** — classe `XxxPayoutProviderClient implements
   PayoutProviderClient` com
   `@ConditionalOnProperty(prefix="foxcash.psp", name="payout-provider",
   havingValue="<adquirente>")` (padrão do `PagarMePixProviderClient` da
   cobrança, Spec 11; use `RestClient` com timeouts e auth):
   - `createPayout` → `POST` no endpoint, devolve `externalReference`;
     falha síncrona lança `PayoutProviderException`/`PayoutProviderTimeoutException`.
   - `getPayout` → consulta e mapeia para `PAID`/`FAILED`/`PENDING`.
   - Se o adquirente usar webhook próprio, ajustar a conversão do payload para o
     canônico do `PayoutWebhookRequest`.
   → **feito para o Pagar.me** (`PagarMePayoutProviderClient`, Spec 47).
5. **Herdar o teste de contrato** — `class XxxPayoutProviderClientTest extends
   PayoutProviderContractTest` (com `MockWebServer` do provedor, padrão do
   `PagarMePixProviderClientTest`): implementa `client()`, `failingClient()` (5xx)
   e `command()`. O contrato é verificado automaticamente.
   → **feito para o Pagar.me** (`PagarMePayoutProviderClientTest` herda e passa).
6. **Preencher as credenciais** (env vars da seção 3) e rodar a suíte:
   `mvn test` + E2E (mock continua default em dev — sem regressão).
   → `mvn test` verde com o harness do adapter real; E2E sem regressão.
7. **Validar a jornada em sandbox:** saque `PROCESSING` → webhook `PAID` →
   `COMPLETED` (evento `WITHDRAWAL_COMPLETED`); `FAILED` → estorno
   (`WITHDRAWAL_REVERSAL` no ledger); reconciliação de órfão (saque preso) →
   consulta `getPayout` e liquida (incluindo `PENDING` que aguarda).

   **Script de homologação** (`load-tests/psp-homologation.py`) — cobre a
   jornada completa de cobrança + saque (22 checks) com o webhook de saída
   assinado e replay idempotente:

   ```bash
   # 7.1. Subir a app com o adapter REAL de PIX de saída:
   SPRING_PROFILES_ACTIVE=dev FOXCASH_PSP_PROVIDER=mock \
     FOXCASH_PSP_PAYOUT_PROVIDER=pagarme \
     FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY=sk_test_... \
     FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET=dev-only-psp-payout-webhook-secret \
     FOXCASH_PSP_PAYOUT_WEBHOOK_VERIFY_SIGNATURE=true \
     mvn spring-boot:run

   # 7.2. Executar a jornada (saque PROCESSING → payout PAID → COMPLETED):
   python load-tests/psp-homologation.py \
     --base-url http://localhost:8080 \
     --provider mock \
     --webhook-secret dev-only-psp-webhook-secret \
     --payout-webhook-secret dev-only-psp-payout-webhook-secret
   ```

   Com credencial sandbox real, o saque é submetido ao Pagar.me (`POST
   /transfers` → `externalReference` = id do transfer) e o webhook de saída
   `PAID` liquida o saque. **Jornada validada ao vivo (mock, 2026-08-07):
   22/22 checks OK** — saque PROCESSING + débito, payout PAID assinado → 204,
   saque COMPLETED, replay payout → 204 idempotente, saldo inalterado.
   → **pendente** (requer credenciais de sandbox reais — passo 7.1).
8. **Switchear em prod** com a jornada validada e monitorar
   `WithdrawalReconciliationScheduler`.
   → pendente (após o passo 7).

## 5. Critérios de aceite da Spec 38 (já atendidos)

1. `PayoutStatus.PENDING` na reconciliação → não liquida PROCESSING (teste
   `pendingOrphanIsSkippedUntilNextRun`).
2. `FOXCASH_PSP_PAYOUT_PROVIDER` sem default em prod (fail-fast no boot).
3. Config `payout-pagarme`/`payout-asaas` + fail-fast na secret-key.
4. `PayoutProviderContractTest` herdado pelo mock (3 invariantes).
5. Este runbook + seção no README.
6. `mvn test` verde (373 + novos) e E2E sem regressão.
