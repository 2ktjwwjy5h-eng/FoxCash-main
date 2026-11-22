---
name: Webhooks outbound v2 — header de evento, assinatura com timestamp e retry policy documentada
status: audited
references:
  - "management/vision.md#V109"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/59-webhook-rate-limit/requirements.md"
  - "management/specs/138-docs-sincronizadas/requirements.md"
---

# Spec 148 — Webhooks outbound v2

## O que entrega

Evolução do contrato de **webhook de saída** (FoxCash → lojista) para o padrão
de mercado (referência: concorrente NyxCash e Stripe-like): (1) header de **tipo
de evento** em toda notificação; (2) assinatura com **timestamp e anti-replay**
(`t=<unix>,v1=<hmac>` — o HMAC cobre `"<t>.<body_cru>"`, permitindo que o lojista
valide a frescura da mensagem); (3) **política de retry documentada** (número de
tentativas, tabela de backoff e regra de 4xx) — hoje o retry existe no outbox
(Spec 35) mas não é contrato público.

1. **Header de evento** — toda notificação passa a enviar `X-FoxCash-Event` com o
   tipo do evento (`sale.paid`, `sale.refunded`, etc. — espelho de
   `WebhookEventType`), além dos headers atuais.
2. **Assinatura com timestamp** — `X-FoxCash-Signature: t=<unix>,v1=<hmac_hex>`,
   onde `hmac = HMAC-SHA256(secret, "<t>.<rawBody>")`. O `t` permite ao lojista
   rejeitar mensagens antigas (janela anti-replay, ex.: 5 min — recomendado na
   documentação).
3. **Retry policy documentada** — `docs/webhooks.md` (ou equivalente) publica a
   política real: tentativas (default do outbox), backoff (ex.: imediato, +1min,
   +5min, +30min...), regra de **4xx encerra tentativas** (erro do cliente) e 5xx/
   timeout continuam retentando. Documentação fiel ao código (padrão Spec 138).
4. **Compatibilidade** — durante janela de transição (90 dias), a assinatura
   antiga (hex puro sem `t=`) continua aceita pelos receptores que não migraram;
   o novo formato passa a ser o default. Documentado na mesma página.

## Critérios de aceite

1. **Header de evento** — toda notificação enviada pelo `HttpMerchantNotifier`
   contém `X-FoxCash-Event` com o tipo real do evento (teste de contrato).
2. **Assinatura v2** — `X-FoxCash-Signature` no formato `t=<unix>,v1=<hex>`; o
   HMAC é computado sobre `"<t>.<rawBody>"`; teste comprova que (a) o formato é
   emitido, (b) a assinatura valida com o segredo do lojista, (c) `t` dentro da
   janela e (d) mudança no body invalida a assinatura.
3. **Retry policy documentada** — a página de webhooks descreve tentativas/
   backoff/regra de 4xx **fiéis ao código** (Spec 138: docs sincronizadas com a
   implementação do outbox).
4. **Compatibilidade** — receptores configurados para o formato legado continuam
   funcionando na janela (config por lojista: `webhook.signature-version` =
   `v1` | `v2`, default v2); flag de transição desligável.
5. **Testes** — contrato (header + formato + validação), unitário do assinador
   v2, docs consistente; `mvn test` verde.

## Dependências

- Spec 35 (outbox + envio — onde o header/assinatura são aplicados).
- Spec 59 (rate limit/429 de webhook — referência de contrato).
- Spec 138 (docs sincronizadas — garante que a retry policy publicada reflete o
  código).
- `WebhookEventType` (domínio — fonte dos valores do header).

## Observações

- **Não altera o recebimento** (PSP → FoxCash): o `WebhookSignatureFilter` é
  inbound e fica intacto; esta spec cobre **somente** o envio (FoxCash → lojista).
- **Janela anti-replay é do receptor** — o FoxCash emite o `t` e documenta a
  recomendação (ex.: rejeitar `t` com mais de 5 min); a validação cabe ao lojista.
- **Decisão de design**: manter o nome `X-FoxCash-Signature` e versionar o
  **formato** (v1 hex puro → v2 `t=,v1=`) em vez de criar header novo — menos
  churn de contrato para quem já valida.
