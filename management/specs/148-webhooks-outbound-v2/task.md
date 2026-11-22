---
name: Webhooks outbound v2 — header de evento, assinatura com timestamp e retry policy documentada
status: audited
---

# Spec 148 — Task: Webhooks outbound v2

Ordem: T1 (signer v2) → T2 (header de evento) → T3 (versionamento) → T4 (docs) →
T5 (testes).

## T1 — Assinatura v2 (critérios 2 e 5)

- [x] T1.1: `WebhookSignatureV2` — serialize `t=<unix>,v1=<hex>` com HMAC sobre
      `"<t>.<rawBody>"` (hex lowercase, `Instant.now().getEpochSecond()`).
- [x] T1.2: `HttpMerchantNotifier` emite o formato v2 (default) e mantém v1
      legado quando configurado.

## T2 — Header de evento (critério 1)

- [x] T2.1: Mapeamento `WebhookEventType` → valor do header (`sale.paid`,
      `sale.refunded`, ...); `HttpMerchantNotifier` envia `X-FoxCash-Event`.

## T3 — Versionamento (critério 4)

- [x] T3.1: Campo `signature_version` no registro de webhook do lojista
      (`v2` default | `v1`); config de transição com janela de 90 dias.

## T4 — Docs (critério 3)

- [x] T4.1: `docs/webhooks.md` — headers, formato da assinatura, validação
      recomendada da janela anti-replay (5 min), e **retry policy fiel ao outbox**
      (tentativas, backoff, 4xx encerra).
- [x] T4.2: `scripts/check-docs-consistency.sh` valida a doc contra a config real
      do outbox (padrão Spec 138).

## T5 — Testes (critério 5)

- [x] T5.1: `WebhookSignatureV2Test` — formato, validação com segredo do lojista,
      body-tamper invalida, `t` correto.
- [x] T5.2: `HttpMerchantNotifierTest` — header `X-FoxCash-Event` presente,
      versão v1/v2 por lojista.
- [x] T5.3: Contrato do envio; `mvn test` verde.
