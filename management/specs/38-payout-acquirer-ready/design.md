# Spec 38 — Design: PIX de saída pronto para o adquirente real

## Contexto

A Spec 36 entregou a porta `PayoutProviderClient` (`createPayout` +
`getPayout`), o `MockPayoutProviderClient`, o webhook `/webhooks/payouts`
(`WithdrawalSettlementService`: PAID → COMPLETED, FAILED → estorno) e a
reconciliação (`WithdrawalReconciliationService`). Para plugar um adquirente
real (Pagar.me/Asaas, ADR-005) sem retrabalho, esta spec fecha três lacunas:
(1) estados intermediários na reconciliação, (2) config por adquirente com
fail-fast de produção, (3) teste de contrato do adapter + runbook.

## Decisões de arquitetura

### 1. `PayoutStatus.PENDING` — reconciliação tolerante a estados reais

```java
// domain/port/out/PayoutProviderClient.java
enum PayoutStatus { PAID, FAILED, PENDING }
```

- Adquirentes reais têm estados intermediários (ex.: Pagar.me payout em
  análise, Asaas `pending`). O adapter mapeia o vocabulário do PSP para o
  canônico: `PAID` (confirmado), `FAILED` (rejeitado) ou `PENDING` (ainda em
  processamento).
- **`WithdrawalReconciliationService.reconcile`** passa a tratar `PENDING` como
  *skip*: o saque `PROCESSING` permanece e o próximo run re-consulta. Hoje
  qualquer estado ≠ PAID vira FAILED → **estorno indevido** do débito.

```java
for (Withdrawal orphan : orphans) {
    PayoutStatus status = payoutProvider.getPayout(orphan.externalReference());
    switch (status) {
        case PAID -> settlement.settleByExternalReference(
                orphan.externalReference(), "PAID", null);
        case FAILED -> settlement.settleByExternalReference(
                orphan.externalReference(), "FAILED", "reconciliacao: falha no PSP");
        case PENDING -> { /* ainda processando: aguarda o próximo run */ }
    }
}
```

- O webhook `/webhooks/payouts` **não muda** (payload canônico `PAID`/`FAILED`);
  o adapter converte no envio. `PayoutStatus.PENDING` é usado apenas na
  **consulta** (`getPayout`), nunca no webhook (que é assíncrono e já carrega o
  desfecho).

### 2. Config por adquirente + fail-fast de produção

`PspProperties` ganha dois blocos (espelhando o `Pagarme` da cobrança):

```java
private final PayoutPsp pagarme = new PayoutPsp();   // payout-pagarme
private final PayoutPsp asaas = new PayoutPsp();     // payout-asaas

public static class PayoutPsp {
    private String secretKey;   // NYX_PSP_PAYOUT_PAGARME_SECRET_KEY (fail-fast quando provider=pagarme)
    private String baseUrl;     // NYX_PSP_PAYOUT_PAGARME_BASE_URL (default do adquirente)
}
```

`validate()` (fail-fast, mesmo padrão da cobrança Spec 10/11):

```java
if ("pagarme".equalsIgnoreCase(payoutProvider)
        && (pagarme.secretKey == null || pagarme.secretKey.isBlank()
            || pagarme.secretKey.contains("${"))) {
    throw new IllegalStateException(
        "foxcash.psp.payout-pagarme.secret-key e obrigatorio quando payout-provider=pagarme (NYX_PSP_PAYOUT_PAGARME_SECRET_KEY)");
}
// análogo para asaas
```

**`application-prod.yml`** — sem default em produção (mock nunca em prod por
acidente):

```yaml
foxcash.psp:
  payout-provider: ${NYX_PSP_PAYOUT_PROVIDER}   # sem default — boot falha se ausente
  payout-pagarme:
    secret-key: ${NYX_PSP_PAYOUT_PAGARME_SECRET_KEY}
    base-url: ${NYX_PSP_PAYOUT_PAGARME_BASE_URL:https://api.pagar.me/core/v5}
  payout-asaas:
    secret-key: ${NYX_PSP_PAYOUT_ASAAS_SECRET_KEY}
    base-url: ${NYX_PSP_PAYOUT_ASAAS_BASE_URL:https://www.asaas.com/api/v3}
```

**`application.yml`** (dev) — mantém `payout-provider: ${NYX_PSP_PAYOUT_PROVIDER:mock}`
+ defaults fake nos blocos (como o `pagarme` da cobrança).

### 3. Teste de contrato do adapter (`PayoutProviderContractTest`)

Harness abstrato que todo adapter real deve **herdar** — valida os invariantes
do contrato com o comportamento independente do provedor:

```java
// src/test/java/com/foxcash/infrastructure/outbound/pix/PayoutProviderContractTest.java
abstract class PayoutProviderContractTest {
    protected abstract PayoutProviderClient client();
    protected abstract PayoutProviderClient.PayoutCommand command();

    @Test void aceiteDevolveExternalReferenceNaoVazia() { ... }
    @Test void consultaDevolveEstadoCanonico() {  // PAID | FAILED | PENDING
        assertThat(client().getPayout("PO_REF")).isIn(PayoutStatus.values());
    }
    @Test void falhaSincronaLancaExcecao() { ... }  // a definir por adapter (config de falha)
}
```

- `MockPayoutProviderClientTest` passa a **estender** o contrato (além dos
  testes específicos de mock-payout-failure).
- O adapter real (spec futura) herda e implementa `client()`/`command()` com o
  `MockWebServer` do provedor (padrão do `PagarMePixProviderClientTest`) — o
  contrato é verificado automaticamente.

### 4. Runbook `deploy/payout-homologation.md`

Documenta (espelhando o `psp-homologation.md` da cobrança):
- O contrato da porta (`PayoutCommand` → `PayoutResult`, `getPayout` e o
  mapeamento de estados do PSP → PAID/FAILED/PENDING).
- O webhook de saída (`POST /webhooks/payouts`, payload canônico, assinatura
  `X-Psp-Signature` HMAC-SHA256 — `foxcash.psp.payout-webhook.*`).
- Configuração por adquirente (env vars + fail-fast).
- **Checklist para plugar o adquirente:** (1) escolher o provider;
  (2) implementar `XxxPayoutProviderClient` com
  `@ConditionalOnProperty(prefix="foxcash.psp", name="payout-provider",
  havingValue="<adquirente>")`; (3) herdar `PayoutProviderContractTest`;
  (4) preencher credenciais; (5) validar jornada em sandbox (saque PROCESSING →
  webhook PAID → COMPLETED; FAILED → estorno; reconciliação).

## Arquivos

- `domain/port/out/PayoutProviderClient.java` (enum `PENDING` + Javadoc do contrato)
- `application/service/WithdrawalReconciliationService.java` (switch PAID/FAILED/PENDING)
- `infrastructure/config/PspProperties.java` (blocos `payout-pagarme`/`payout-asaas` + validate)
- `src/main/resources/application.yml` (blocos payout dev + defaults fake)
- `src/main/resources/application-prod.yml` (sem default + env vars)
- `src/test/.../PayoutProviderContractTest.java` (novo harness)
- `src/test/.../MockPayoutProviderClientTest.java` (estende o contrato)
- `src/test/.../WithdrawalReconciliationServiceTest.java` (+ teste PENDING)
- `deploy/payout-homologation.md` (novo runbook)
- `README.md` (seção de PIX de saída)

## Fora de escopo

- O **adapter real** do adquirente (spec futura quando o adquirente for
  definido — segue o runbook e o teste de contrato).
- Motivo de falha no `getPayout` (enum simples; o motivo real chega pelo webhook
  `FAILED` — `failedReason`).
- Novos endpoints de saque (fluxo inalterado).
