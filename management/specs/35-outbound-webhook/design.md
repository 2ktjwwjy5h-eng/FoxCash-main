# Spec 35 — Design: Webhook de saída para o bot do lojista

## Contexto

Hoje `MerchantNotifier` é um adapter mock (`LoggingMerchantNotifier`) que só loga,
e o outbox (Spec 7) tem um único emissor (`INVOICE_GENERATED` em
`GenerateInvoiceService`). O bot do lojista **consulta** o status em vez de ser
**avisado**. Esta spec troca a entrega por **HTTP assinado** (HMAC-SHA256) quando
o lojista configura um webhook, evolui o outbox com controle de retry, e publica
os eventos de venda/estorno/saque/fatura nos pontos de origem (mesma transação).

## Decisões de arquitetura

### Migration V24 — `merchant_webhooks` + evolução do outbox

```sql
CREATE TABLE merchant_webhooks (
    id           UUID PRIMARY KEY,
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    url          VARCHAR(500) NOT NULL,
    secret       VARCHAR(255) NOT NULL,     -- criptografado AES-256/GCM (v1:)
    events       VARCHAR(255) NOT NULL,     -- lista CSV de tipos (ex.: SALE_PAID,CHARGE_CREATED)
    status       VARCHAR(20) NOT NULL,      -- ACTIVE | INACTIVE
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
CREATE INDEX ix_merchant_webhooks_merchant ON merchant_webhooks(merchant_id);
CREATE INDEX ix_merchant_webhooks_merchant_status ON merchant_webhooks(merchant_id, status);

-- Outbox ganha controle de entrega/retry.
ALTER TABLE notification_outbox
    ADD COLUMN attempts       INT NOT NULL DEFAULT 0,
    ADD COLUMN last_error     VARCHAR(500),
    ADD COLUMN next_attempt_at TIMESTAMP;
CREATE INDEX ix_notification_outbox_pending ON notification_outbox(status, next_attempt_at);
```

- `secret` usa `@Convert(converter = CryptoConverter.class)` — nunca em claro no
  banco nem em resposta (padrão Spec 4/31).
- `events` valida contra o enum `WebhookEventType` (400 `invalid_event`).

### Domínio

```java
// domain/model/notification/WebhookEventType.java
// CHARGE_CREATED, SALE_PAID, SALE_REFUNDED, CHARGEBACK_REGISTERED,
// WITHDRAWAL_COMPLETED, INVOICE_GENERATED, INVOICE_COLLECTED

// domain/model/notification/MerchantWebhook.java
// record (id, merchantId, url, secretEncrypted, events, status)
// com create(...)/activate()/deactivate()/isSubscribedTo(WebhookEventType)
```

### Publicação — `WebhookEventPublisher` (mesma transação)

```java
// application/service/WebhookEventPublisher.java
public void publish(UUID merchantId, WebhookEventType type, Object data) {
    String payload = jsonMapper.writeValueAsString(new WebhookEnvelope(
        UUID.randomUUID(), type, merchantId, Instant.now(), data)); // eventId p/ idempotência
    outbox.savePending(OutboxNotification.pending(merchantId, type.name(), payload));
}
```

Chamado nos pontos de origem **dentro do `@Transactional`** existente (nunca em
transação nova):

| Origem | Evento |
|---|---|
| `MerchantChargeService` (cobrança criada) | `CHARGE_CREATED` |
| webhook de pagamento (Spec 10/11) ao `APPROVED` | `SALE_PAID` |
| estorno (Spec 29) ao `REFUNDED` | `SALE_REFUNDED` |
| chargeback (Spec 29) registrado | `CHARGEBACK_REGISTERED` |
| `WithdrawalService`/master (Spec 32) | `WITHDRAWAL_COMPLETED` |
| `GenerateInvoiceService` (já existe) | `INVOICE_GENERATED` (mantém) |
| `InvoiceCollectionService` (Spec 33) | `INVOICE_COLLECTED` |

Payload padronizado: `{eventId, type, merchantId, createdAt, data:{...}}` —
`data` varia por evento (ex.: `SALE_PAID` → `{transactionId, amount, paidAt}`).

### Entrega — `HttpMerchantNotifier` (novo adapter de `MerchantNotifier`)

```java
// infrastructure/notification/HttpMerchantNotifier.java
public void deliver(OutboxNotification notification) {
    webhooks.findAllActiveByMerchantAndEvent(merchantId, type).forEach(webhook -> {
        String body = notification.payload();
        String signature = hmacSha256(webhook.secret(), body); // hex
        httpClient.post(webhook.url())
            .header("X-FoxCash-Signature", signature)
            .header("X-FoxCash-Event-Id", notification.id())
            .header("X-FoxCash-Event", notification.type())
            .body(body).execute();
    });
}
```

- **Fallback:** se o lojista não tem webhook ativo para o evento, cai no
  `LoggingMerchantNotifier` (log) — sem quebra do fluxo atual.
- 2xx → sucesso; qualquer erro/4xx/5xx/timeout → falha (dispara retry).

### Retry com backoff — `NotificationOutboxPort` evoluída

```java
// domain/port/out/NotificationOutboxPort.java
List<OutboxNotification> findPendingDue(Instant now);   // status=PENDING AND next_attempt_at <= now
void markFailed(UUID id, String error, Instant nextAttemptAt);
void incrementAttempts(UUID id);
int maxAttempts();                                       // foxcash.notification.max-attempts (default 5)
```

- `NotificationDispatchScheduler` (padrão Spec 7, `fixedDelayString` PT30S)
  processa `findPendingDue(now)`.
- Sucesso → `markSent` (status `SENT`, `sent_at`). Falha → `incrementAttempts`
  + `markFailed(last_error, next_attempt_at = now + backoff(attempt))` com
  backoff exponencial (`2^attempt * baseDelay`, base default 30s). Após
  `maxAttempts` → status `FAILED` e **não re-entrega** (o backstop é a
  reconciliação da Spec 30 / consulta do lojista).

### Teste de entrega

`POST /api/v1/portal/webhooks/test` (body `{url, secret, eventType?}`) → envia um
ping assinado (payload `{eventId, type: "PING", ...}`) via
`HttpMerchantNotifier.testDelivery(url, secret)` e responde 200 com
`{delivered: true/false, statusCode?, error?}` — **não persiste** nada.

### REST (portal, `ROLE_MERCHANT_PORTAL`, principal = merchantId)

| Rota | Resposta |
|---|---|
| `GET /api/v1/portal/webhooks` | lista (secret **nunca** exposto; `hasSecret: true`) |
| `POST /api/v1/portal/webhooks` | 201 (gera secret aleatório na criação) |
| `PATCH /api/v1/portal/webhooks/{id}` | atualiza url/events (404 cross-loja) |
| `POST /api/v1/portal/webhooks/{id}/activate` \| `/deactivate` | 204 |
| `POST /api/v1/portal/webhooks/test` | 200 `{delivered, statusCode?, error?}` |

Segredo: gerado pelo servidor (32 bytes hex) na criação, devolvido **uma única
vez** no 201; nas demais respostas apenas `hasSecret`.

## Arquivos

- Migration `V24__merchant_webhooks.sql`
- `domain/model/notification/WebhookEventType.java`, `MerchantWebhook.java`
- `domain/port/out/MerchantWebhookRepository.java` + adapter JPA
- `NotificationOutboxPort` evoluída (findPendingDue/markFailed/incrementAttempts)
  + `NotificationOutboxJpaEntity` (attempts/last_error/next_attempt_at) + adapter
- `application/service/WebhookEventPublisher.java` (novo)
- `infrastructure/notification/HttpMerchantNotifier.java` (novo adapter)
- `NotificationDispatcherService` (usa findPendingDue + retry/backoff)
- `infrastructure/inbound/rest/PortalWebhookController.java` + DTOs
- Alterados: `MerchantChargeService`, webhook de pagamento (Spec 10/11),
  `RegisterChargebackService`, `WithdrawalService` (master e lojista),
  `InvoiceCollectionService` (publicam eventos no outbox)
- Testes: `HttpMerchantNotifierTest` (assinatura reproduzível), 
  `WebhookEventPublisherTest`, `NotificationDispatcherServiceTest` (retry),
  `PortalWebhookControllerTest` (CRUD privado, secret oculto)
- E2E: cenário 15 (configurar webhook, receber `SALE_PAID` assinado, retry)

## Fora de escopo

- Fila externa (SQS/Rabbit) — o polling com backoff cobre o volume atual.
- Re-entrega manual de `FAILED` — fica para spec futura de operação.
- Portal de gestão de webhooks no frontend (fase futura de frontend).
