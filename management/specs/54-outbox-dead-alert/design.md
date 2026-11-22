# Spec 54 — Design: Alerta de evento de outbox esgotado (OUTBOX_DEAD)

## Contexto

O pipeline do outbox (Spec 35) entrega eventos de webhook de saída com
retry/backoff exponencial (base 30s, max 5 tentativas). Ao esgotar, o evento
vira `FAILED` e **fica silencioso** no banco: o operador só descobre consultando
a tabela. A Spec 53 entregou o **webhook de operador** (eventos de plataforma
com `merchantId=null` → `HttpMerchantNotifier.deliverToOperator`) e o
`notification_outbox.merchant_id` nullable (V43). Esta spec usa ESSA infra para
alertar o esgotamento — fechando o cenário 5 dos fluxos do outbox sem criar uma
DLQ física.

## Decisões de arquitetura

### 1. Onde disparar — `NotificationProcessor.handleFailure`

A transição para `FAILED` acontece no branch `attempts >= maxAttempts` do
`NotificationProcessor` (que já roda em transação própria por evento). É o
ponto ÚNICO e correto:

```java
if (attempts >= properties.getMaxAttempts()) {
    outbox.markFailed(notification.id(), message, null);   // → FAILED
    publishDeadLetter(notification, message, attempts);    // → alerta
} else {
    // retry/backoff (sem alerta)
}
```

Publicar **na mesma transação** dá atomicidade: o `FAILED` e o `OUTBOX_DEAD`
commitam juntos (nada de alerta perdido ou evento morto sem aviso).

### 2. Evento `OUTBOX_DEAD` (plataforma) + payload

- `WebhookEventType.OUTBOX_DEAD` (novo enum).
- `eventPublisher.publish(null, OUTBOX_DEAD, data)` — `merchantId=null` →
  entrega ao webhook de OPERADOR (não ao do lojista — um `OUTBOX_DEAD` com
  merchantId não-nulo iria aos webhooks do lojista, que não assinam o evento →
  fallback log). O merchantId do evento morto vai **no payload**, não na coluna.
- Payload (`HashMap` — `Map.of` não aceita null): `eventId` (string, id do
  evento morto), `originalType`, `merchantId` (string ou null), `error`,
  `attempts`.

### 3. Anti-cascata

Se o evento morto JÁ é `OUTBOX_DEAD`, não republica (WARN no log). Sem isso,
um operador fora do ar geraria `OUTBOX_DEAD → OUTBOX_DEAD → ...` infinito
(teoricamente convergiria, mas inunda o banco).

### 4. Resiliência do alerta

`publishDeadLetter` em try/catch WARN: falha ao publicar o alerta não quebra o
processamento nem mascara o erro original. Sem operador configurado, o
`deliverToOperator` faz fallback log e o `OUTBOX_DEAD` vai a `SENT` — o alerta
não fica acumulando PENDING.

### 5. Sem DLQ física (decisão)

O `FAILED` permanece na mesma `notification_outbox` (status + `last_error` +
`attempts` + payload) — a própria tabela é a trilha. O `findPendingDue` só pega
`PENDING`, então o evento morto nunca é reprocessado. Uma "DLQ de consulta"
(endpoint de operador listando `FAILED`) e re-enfileiramento manual ficam como
evolução futura (fora de escopo).

## Arquivos

- `domain/model/notification/WebhookEventType.java` (+`OUTBOX_DEAD`)
- `application/service/NotificationProcessor.java` (+`WebhookEventPublisher`
  injetado + `publishDeadLetter`)
- `src/test/java/.../NotificationProcessorTest.java` (+3 testes)
- `e2e-tests/tests/outbox-flow.spec.js` (cenário 5 estendido: valida a
  publicação do `OUTBOX_DEAD`)
- `management/vision.md` (V34) · `management/roadmap.md` (Spec 54)
