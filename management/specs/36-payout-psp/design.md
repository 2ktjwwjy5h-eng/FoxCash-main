# Spec 36 — Design: Saque via PSP (Pix de saída)

## Contexto

O saque (Spec 32) marca `COMPLETED` mas não movimenta dinheiro de verdade. O
usuário adquirente real **depois**; esta spec prepara o contrato e a estrutura:
porta `PayoutProviderClient` (padrão Spec 10/11), estado real do saque
(`PROCESSING → COMPLETED | FAILED`), webhook de saída assinado e reconciliação.
O **débito continua imediato** na solicitação (decisão 2026-08-04) — em `FAILED`
o valor é estornado ao saldo.

## Decisões de arquitetura

### Migration V25 — estado real do saque

```sql
ALTER TABLE withdrawals
    ADD COLUMN status             VARCHAR(20) NOT NULL DEFAULT 'COMPLETED', -- PROCESSING | COMPLETED | FAILED
    ADD COLUMN external_reference VARCHAR(100),
    ADD COLUMN payout_provider    VARCHAR(30),                               -- MOCK | PAGARME | ASAAS | ...
    ADD COLUMN failure_reason     VARCHAR(255),
    ADD COLUMN completed_at       TIMESTAMP,
    ADD COLUMN failed_at          TIMESTAMP;
CREATE INDEX ix_withdrawals_status_created ON withdrawals(status, created_at);
```

- Migração de dados: saques existentes `COMPLETED` permanecem `COMPLETED`
  (o default cobre). A partir daqui, novos saques nascem `PROCESSING`.

### Domínio — máquina de estados

```java
// domain/model/withdrawal/WithdrawalStatus.java -> PROCESSING, COMPLETED, FAILED
// domain/model/withdrawal/Withdrawal.java (evolui)
public void submitToPsp(String externalReference, String provider) { // COMPLETED->PROCESSING
    require(this.status == COMPLETED);  // nasce COMPLETED na Spec 32; aqui passa a nascer PROCESSING
    this.status = PROCESSING; this.externalReference = externalReference; this.payoutProvider = provider;
}
public void complete(Instant now)   { require(PROCESSING); status = COMPLETED; completedAt = now; }
public void fail(String reason, Instant now) { require(PROCESSING); status = FAILED; failureReason = reason; failedAt = now; }
```

> Nota de design: o saque passa a **nascer `PROCESSING`** (o default `COMPLETED`
> da migration só preserva o histórico). A Spec 32 (não implementada ainda) já
> deve nascer com este fluxo — a 36 é a evolução final do modelo.

### Porta `PayoutProviderClient` (novo, padrão Spec 10/11)

```java
// domain/port/out/PayoutProviderClient.java
public interface PayoutProviderClient {
    PayoutResult createPayout(PayoutCommand command);

    record PayoutCommand(UUID withdrawalId, Money amount,
                         PayoutDestination destination, String description) {}
    record PayoutDestination(String pixKey /* desencriptada no adapter */,
                             String bankCode, String agency, String account,
                             String accountType) {}
    record PayoutResult(String externalReference) {}
}
```

- `MockPayoutProviderClient` (`@ConditionalOnProperty(prefix="foxcash.psp",
  name="payout-provider", havingValue="mock", matchIfMissing=true)`): devolve
  `externalReference` fake; simula falha se
  `foxcash.psp.mock-payout-failure=true` (para testar o estorno).
- Adapter real plugável por `foxcash.psp.payout-provider` (ex. `pagarme-payout`
  futuro) — `@PostConstruct validate()` falha se o provider for desconhecido
  (fail-fast, padrão `PspProperties`).

### Solicitação de saque (Spec 32 integra)

`WithdrawalService.withdraw` / `MasterWithdrawalService.administrativeWithdraw`:

```java
@Transactional
public WithdrawalResult withdraw(WithdrawalCommand cmd) {
    Merchant merchant = merchantRepository.findByIdForUpdate(cmd.merchantId()).orElseThrow(404);
    // validações existentes (destino ativo/posse, bloqueio, dívida, saldo) ...
    Withdrawal withdrawal = merchant.withdraw(cmd.amount());            // debita (ledger WITHDRAWAL_DEBIT)
    PayoutResult result = payoutProvider.createPayout(toPayoutCommand(withdrawal));
    withdrawal.submitToPsp(result.externalReference(), providerName());
    withdrawalRepository.save(withdrawal);
    return WithdrawalResult.ok(withdrawal);
}
```

- **Débito imediato + envio ao PSP na mesma transação.** Se o provider lançar
  erro **síncrono** (timeout/5xx na chamada inicial), a transação faz rollback —
  o saque **não** é criado nem debitado; o lojista repete. (Falha **após
  aceite** é tratada pelo webhook/reconciliação, não por rollback.)
- Destino: a chave/dados bancários são desencriptados **só dentro do adapter**
  (o serviço de aplicação nunca vê o valor em claro além do snapshot já
  gravado criptografado na Spec 32).

### Webhook de saída — `ProcessPayoutWebhookService`

`POST /webhooks/payouts` (assinatura via `WebhookSignatureFilter` reutilizado —
segredo `foxcash.psp.payout-webhook.secret`, rota `/webhooks/payouts`):

```java
@Transactional
public void process(PayoutWebhookEvent event) {   // (externalReference, status, eventId, failedReason?)
    Withdrawal w = withdrawalRepository.findByExternalReferenceForUpdate(event.externalReference()).orElseThrow(404);
    if (w.status() != PROCESSING) return;                                  // idempotente
    if ("PAID".equals(event.status())) {
        w.complete(now);
    } else if ("FAILED".equals(event.status())) {
        w.fail(event.failedReason(), now);
        merchantRepository.findByIdForUpdate(w.merchantId()).ifPresent(m -> {
            m.credit(w.amount());                                           // estorno do débito
            ledgerRecorder.record(m, WITHDRAWAL_REVERSAL, w.amount(), "WITHDRAWAL", w.id()); // Spec 27
        });
    }
    withdrawalRepository.save(w);
}
```

- **Idempotência:** gate de status (`!= PROCESSING` → no-op) + lock pessimista
  `findByExternalReferenceForUpdate`.
- **Estorno nunca duplica:** o `WITHDRAWAL_REVERSAL` só acontece na transição
  `PROCESSING → FAILED`.

### Reconciliação — `WithdrawalReconciliationService`

Job ShedLock `foxcash-payout-reconciliation` (cron configurável, padrão diário
04:00 UTC — fora da janela de pico de saques):

- Busca `PROCESSING` com `created_at < now - limite` (ex. 2h, configurável).
- Para cada um, consulta o PSP (`payoutProvider.getPayout(externalReference)`
  — nova porta opcional; o mock retorna `PAID` salvo falha simulada).
- `PAID` → `complete`; `FAILED` → `fail` + estorno (mesmo fluxo do webhook,
  extraído em método compartilhado `settleOutcome`).
- Loga divergências para o job da Spec 30/auditoria.

### Properties (`PspProperties` estendido)

```yaml
foxcash:
  psp:
    payout-provider: ${NYX_PSP_PAYOUT_PROVIDER:mock}      # mock | (futuro) pagarme
    mock-payout-failure: ${NYX_MOCK_PAYOUT_FAILURE:false}  # simula falha p/ teste
    payout-webhook:
      secret: ${NYX_PSP_PAYOUT_WEBHOOK_SECRET:}
      verify-signature: false
    payout-reconciliation:
      cron: "0 30 4 * * *"         # UTC
      pending-since-hours: 2
```

## Arquivos

- Migration `V25__withdrawal_payout_state.sql`
- `domain/model/withdrawal/WithdrawalStatus.java`, `Withdrawal` (submitToPsp/
  complete/fail), `domain/port/out/PayoutProviderClient.java`
- `infrastructure/outbound/pix/MockPayoutProviderClient.java` (novo)
- `infrastructure/config/PspProperties` (payout-webhook/reconciliation)
- `application/service/WithdrawalService` / `MasterWithdrawalService` (integra
  provider, nasce PROCESSING)
- `application/service/ProcessPayoutWebhookService.java` (novo) +
  `infrastructure/inbound/webhook/PayoutWebhookController.java` (novo)
- `application/service/WithdrawalReconciliationService.java` (novo) +
  `infrastructure/scheduler/WithdrawalReconciliationScheduler.java` (novo)
- `WithdrawalJpaEntity`/mapper (colunas novas)
- Testes: `PayoutWebhookServiceTest` (PAID/FAILED/estorno/idempotência),
  `WithdrawalServiceTest` (nasce PROCESSING, provider erro → rollback),
  `WithdrawalReconciliationServiceTest` (encerra órfãos sem duplicar)
- E2E: cenários 16 (mock → PROCESSING → COMPLETED) e 17 (falha → estorno)

## Fora de escopo

- Adapter real de adquirente (Pagar.me/Asaas de saída) — **spec futura** quando
  o usuário escolher o adquirente; o slot está pronto.
- TED/boleto como destino — só Pix e dados bancários (Spec 31).
- Reserva de valor / retenção — spec futura.
