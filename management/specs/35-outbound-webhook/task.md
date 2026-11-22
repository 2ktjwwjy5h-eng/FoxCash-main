# Spec 35 — Task breakdown

Status: `implemented` (2026-08-04). Implementação concluída — checklist abaixo.

## Implementação

- [x] Migration `V27__merchant_webhooks.sql` (o número avançou: V24/V25 ficaram
      para as specs 36/37 — V25 não criada; V26 = `master_audit_log`): tabela
      `merchant_webhooks` (+ índices `(merchant_id)` e `(merchant_id, status)`)
      e `notification_outbox` ganha `attempts`/`last_error`/`next_attempt_at`
      (+ índice `(status, next_attempt_at)`).
- [x] `WebhookEventType` (enum dos 7 eventos) + `MerchantWebhook` (record de
      domínio com create/activate/deactivate/isSubscribedTo).
- [x] `MerchantWebhookRepository` (port out: findAllActiveByMerchantAndEvent,
      findByMerchantIdAndId, findAllByMerchantId, save) +
      `MerchantWebhookJpaEntity` (secret com `@Convert(CryptoConverter.class)`)
      + mapper (events CSV) + adapter.
- [x] `NotificationOutboxPort` evoluída: `findPendingDue(Instant)`,
      `incrementAttempts`, `markFailed(id, error, nextAttemptAt)` +
      `NotificationOutboxJpaEntity` (colunas novas) + adapter.
- [x] `WebhookEventPublisher` (application): monta envelope
      `{eventId, type, merchantId, createdAt, data}` e grava no outbox na mesma
      transação.
- [x] `HttpMerchantNotifier` (adapter de `MerchantNotifier`, `@Primary`): POST
      assinado HMAC-SHA256 (headers `X-FoxCash-Signature`/`X-FoxCash-Event-Id`/
      `X-FoxCash-Event`); fallback para `LoggingMerchantNotifier` quando o
      lojista não tem webhook ativo para o evento; `testDelivery(url, secret)`.
- [x] `NotificationDispatcherService`: usa `findPendingDue(now)`; sucesso →
      `markSent`; falha → `incrementAttempts` + backoff exponencial
      (`next_attempt_at = now + base * 2^attempt`); após
      `foxcash.notification.max-attempts` (default 5) → `FAILED`.
- [x] Publicar eventos nas origens (mesma transação): `CreatePixChargeService`
      (`CHARGE_CREATED`), `ProcessPixWebhookService` (`SALE_PAID`/
      `SALE_REFUNDED`), `RegisterChargebackService`
      (`CHARGEBACK_REGISTERED`), `WithdrawalService`/`MasterWithdrawalService`
      (`WITHDRAWAL_COMPLETED`), `GenerateInvoiceService`
      (`INVOICE_GENERATED` — mantido, agora envelope), `InvoiceCollectionService`
      (`INVOICE_COLLECTED`).
- [x] `PortalWebhookController` (`/api/v1/portal/webhooks`,
      ROLE_MERCHANT_PORTAL): GET (sem secret, `hasSecret`), POST 201 (secret
      gerado, devolvido uma única vez), PATCH /{id}, POST /{id}/activate|
      deactivate 204, POST /test.
- [x] Propriedades: `foxcash.notification.max-attempts` (5),
      `foxcash.notification.backoff-base-ms` (30000).

## Verificação

- [x] `HttpMerchantNotifierTest`: assinatura HMAC-SHA256 reproduzível com o
      segredo; 2xx → entrega ok; 5xx → falha (retry); fallback log sem webhook;
      teste de entrega ok/falha.
- [x] `WebhookEventPublisherTest`: envelope completo com `eventId` único;
      payload JSON válido; grava PENDING na mesma transação.
- [x] `NotificationDispatcherServiceTest`: retry com backoff exponencial
      (≈ base×2^attempt); `maxAttempts` → FAILED com `last_error`; re-execução
      não re-entrega FAILED.
- [x] `PortalWebhookControllerTest`: CRUD privado (404 cross-loja via exceção de
      domínio), evento inválido → 400 `invalid_event`, secret nunca em claro,
      teste de entrega → `{delivered, statusCode?, error?}`.
- [x] Publicação ponta a ponta: cobrança criada → `CHARGE_CREATED`; webhook PAID
      → `SALE_PAID`; estorno → `SALE_REFUNDED`; saque → `WITHDRAWAL_COMPLETED`;
      fatura paga → `INVOICE_COLLECTED` (verificado no outbox, unit + E2E).
- [x] E2E: cenário 15 (configurar webhook, receber `SALE_PAID` assinado, retry
      em falha) — suite **16/16** sem regressão.
- [x] `mvn test` verde (**299 testes, 0 falhas** — 284 + 15 novos: 2
      `WebhookEventPublisherTest`, 6 `PortalWebhookControllerTest`, 5
      `HttpMerchantNotifierTest`, 2 novos no `NotificationDispatcherServiceTest`).
