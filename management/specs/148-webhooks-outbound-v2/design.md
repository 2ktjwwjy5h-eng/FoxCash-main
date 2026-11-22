---
name: Webhooks outbound v2 — header de evento, assinatura com timestamp e retry policy documentada
status: audited
---

# Spec 148 — Design: Webhooks outbound v2

## D1 — Conceito

O envio (FoxCash → lojista) hoje é feito pelo `HttpMerchantNotifier` (Apache
HttpClient 5) com assinatura `X-FoxCash-Signature` = hex puro do
HMAC-SHA256(secret, rawBody). A v2 mantém o mesmo header, mas o **valor** vira
`t=<unix>,v1=<hex>` (HMAC sobre `"<t>.<rawBody>"`) e adiciona `X-FoxCash-Event`.
O retry já existe no outbox (Spec 35) — esta spec o torna **contrato público**.

```
POST {merchant.webhookUrl}
  headers:
    X-FoxCash-Event: sale.paid
    X-FoxCash-Signature: t=1724...,v1=<hex>
  body: {event: "sale.paid", transactionId: "...", amount: 50.00, ...}
```

## D2 — Assinatura v2 (signer)

- `WebhookSignatureV2.serialize(t, payload) → "t=<t>,v1=<hex>"` onde
  `hex = HMAC-SHA256(secret, "<t>.<rawBody>")` (hex lowercase).
- Reuso de `Mac`/`HexFormat` (mesmo padrão do `WebhookSignatureFilter`
  inbound, Spec 10/11). `t` = epoch seconds (UTC, `Instant.now().getEpochSecond()`).

## D3 — Versionamento por lojista

- Config nova por lojista/registro de webhook: `signature_version` (`v2` default |
  `v1` legado), persistida no registro de webhook do lojista. Na janela de
  transição (90 dias), `v1` continua emitindo o formato antigo; ao final, `v1`
  é removido (decisão de remoção documentada — Spec 138).

## D4 — Header de evento

`HttpMerchantNotifier` ganha o header `X-FoxCash-Event` com o valor do
`WebhookEventType` (`SALE_PAID → "sale.paid"`, `SALE_REFUNDED → "sale.refunded"`,
etc.) — mapeamento 1:1 centralizado (mesmo enum do payload `event`).

## D5 — Retry policy documentada

- Nova página `docs/webhooks.md`: tabela de backoff **lida do código** do outbox
  (Spec 35) — nº de tentativas, `next_attempt_at = now + base × 2^attempt`,
  regra **4xx encerra** (erro do cliente), 5xx/timeout/erro de rede continuam.
- `scripts/check-docs-consistency.sh` (padrão Spec 138) valida que a doc reflete
  os valores reais (tentativas/backoff da config).

## D6 — Arquivos (principais)

- `HttpMerchantNotifier.java` (header de evento + signer v2 + versionamento),
  `WebhookSignatureV2.java` (novo), registro de webhook do lojista
  (+ `signature_version`)
- `docs/webhooks.md` (novo — contrato público: headers, formato, validação
  recomendada da janela anti-replay, retry policy)
- `WebhookEventType.java` (mapeamento de valores do header)
- Testes: `WebhookSignatureV2Test` (formato/validação/body-tamper),
  `HttpMerchantNotifierTest` (header + versão), contrato do envio
