---
name: Alerta de evento de outbox esgotado (OUTBOX_DEAD)
status: audited
references:
  - "management/vision.md#V34"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 54 — Alerta de evento de outbox esgotado (OUTBOX_DEAD)

## O que entrega

Quando uma entrega de webhook de saída (Spec 35) **esgota as tentativas**
(`attempts >= max`, default 5 → status `FAILED`), o sistema publica um alerta
operacional para que o evento perdido não seja **silêncio**:

1. **Evento `OUTBOX_DEAD`** — novo `WebhookEventType` de PLATAFORMA (sem
   merchant). Publicado pelo `NotificationProcessor` **na MESMA transação** do
   `markFailed(null)` (FAILED) — atomicidade: ou o evento morre E o alerta é
   gerado, ou nenhum dos dois.
2. **Payload** — `{eventId, originalType, merchantId, error, attempts}` do
   evento morto, para o operador saber QUAL evento, de QUEM, com QUAL erro.
3. **Entrega** — o `OUTBOX_DEAD` é um evento de plataforma (`merchantId=null`)
   → entregue ao **webhook de operador** pela infra da Spec 53
   (`HttpMerchantNotifier.deliverToOperator`, HMAC do secret do operador,
   retry/backoff do pipeline). Sem operador configurado → fail-open (fallback
   log + SENT — o alerta não acumula).
4. **Anti-cascata** — um `OUTBOX_DEAD` que também esgota NÃO gera outro
   `OUTBOX_DEAD` (evita cadeia infinita de alertas sobre alertas).
5. **Sem DLQ física** — o evento `FAILED` permanece na própria tabela como
   trilha auditável (status + `last_error` + `attempts` + payload). O alerta é
   o mecanismo de visibilidade; uma DLQ/consultas de operador ficam como
   evolução futura.

## Critérios de aceite

- Evento de merchant (ex.: `SALE_PAID`) que esgota as tentativas →
  `FAILED` + **`OUTBOX_DEAD` PENDING publicado na mesma transação**, com
  `data.eventId` = id do evento morto, `data.originalType` = tipo original,
  `data.merchantId` = dono, `data.error` (contém o motivo), `data.attempts` =
  máximo atingido.
- Com tentativas ainda disponíveis (retry/backoff) → **NÃO** publica
  `OUTBOX_DEAD`.
- `OUTBOX_DEAD` que esgota → **NÃO** publica outro (anti-cascata).
- O `OUTBOX_DEAD` nasce com `merchant_id` NULL (plataforma) → a entrega vai ao
  webhook de operador (assinado) ou fallback log sem operador.
- `mvn test` verde (novos no `NotificationProcessorTest`) e E2E
  `outbox-flow.spec.js` cenário 5 validando a publicação do alerta.

## Dependências

- `management/specs/35-outbound-webhook/requirements.md` (pipeline do outbox —
  retry/backoff, `FAILED`; a origem da transição)
- `management/specs/53-job-robustez/requirements.md` (webhook de operador —
  `foxcash.notification.operator-webhook-*` + `merchant_id` nullable no outbox,
  V43)
- `e2e-tests/tests/outbox-flow.spec.js` (cenário 5 — comportamento validado)
