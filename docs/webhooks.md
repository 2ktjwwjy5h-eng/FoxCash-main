# Webhooks de saída (FoxCash → lojista)

Contrato público do webhook de saída (Spec 35 + Spec 148). Este documento
espelha a implementação — qualquer divergência é bug (padrão Spec 138: docs
sincronizadas com o código).

## Formato da notificação

Quando um evento ocorre, o FoxCash faz um `POST` assinado para cada webhook
**ativo** do lojista inscrito naquele tipo de evento. O corpo é o envelope JSON
original (payload cru) e o transporte é HTTPS com o segredo do lojista.

Headers:

| Header | Obrigatório | Descrição |
|---|---|---|
| `Content-Type` | sim | `application/json` |
| `X-FoxCash-Signature` | sim | Assinatura HMAC-SHA256 do corpo (formato versionado, abaixo) |
| `X-FoxCash-Event-Id` | sim | UUID do evento (deduplicação — at-least-once) |
| `X-FoxCash-Event` | sim | Tipo do evento em formato de mercado: `sale.paid`, `sale.refunded`, etc. (espelho de `WebhookEventType`) |

## Assinatura

A assinatura é HMAC-SHA256 com o segredo do lojista, e o formato é **versionado
por lojista** (configuração `signature_version`):

### v2 (default) — timestamp + anti-replay

```
X-FoxCash-Signature: t=<unix>,v1=<hex>
```

onde `hex = HMAC-SHA256(secret, "<t>.<rawBody>")` e `t` é o epoch seconds da
emissão.

O `t` permite ao receptor validar a **frescura** da mensagem: recomenda-se
rejeitar mensagens com `t` mais de 5 minutos no passado (ou no futuro). A
validação da janela é responsabilidade do lojista.

### v1 (legado) — hex puro

```
X-FoxCash-Signature: <hex>
```

onde `hex = HMAC-SHA256(secret, rawBody)`. Mantido durante a **janela de
transição de 90 dias** para receptores que ainda não migraram; `v1` será
removido ao final da janela. Receptores devem migrar para `v2`.

## Validação (receptor)

1. Rejeite se o corpo não estiver íntegro: recompute o HMAC com o segredo e
   compare com o header (constant-time). No formato v2, o HMAC cobre
   `"<t>.<rawBody>"` — não o body puro.
2. No formato v2, rejeite `t` antigo (janela anti-replay recomendada: 5 min) ou
   no futuro.
3. Deduplique pelo `X-FoxCash-Event-Id` — a entrega é **at-least-once**; o
   mesmo evento pode chegar mais de uma vez.

## Retry policy (fiel ao código)

A entrega é processada pelo outbox (Spec 35/53). Comportamento **real** do
`NotificationProcessor` + `HttpMerchantNotifier` (defaults configuráveis em
`NotificationProperties`):

| Situação | Comportamento |
|---|---|
| `2xx`/`3xx` | Sucesso — evento marcado `SENT` |
| `4xx` | **Falha PERMANENTE** — erro do cliente (URL/config errada, payload rejeitado). Retentar não resolve: o evento é marcado `FAILED` **imediatamente, sem backoff** |
| `5xx`, timeout, erro de rede | Falha transitória — **retry com backoff exponencial** |
| Tentativas esgotadas | Evento marcado `FAILED` e um alerta `OUTBOX_DEAD` é publicado no webhook de operador (Spec 35/53) |

Tentativas e backoff (defaults):

- **Máximo de tentativas:** `5`
- **Backoff exponencial:** `retryAt = agora + 30000ms × 2^attempts` (base 30s),
  na prática: ~1min, ~2min, ~4min, ~8min, ~16min antes de esgotar.

> O backoff usa o contador REAL de tentativas (leitura fresca na transação) —
> não um valor carregado no início do processamento.

### Exemplo (5xx contínuo)

1. 1ª falha → retry em ~1min
2. 2ª falha → retry em ~2min
3. 3ª falha → retry em ~4min
4. 4ª falha → retry em ~8min
5. 5ª falha (esgotou) → `FAILED` + alerta `OUTBOX_DEAD`

Qualquer `4xx` interrompe essa sequência imediatamente (`FAILED` sem retry).

## Compatibilidade (janela de transição)

- Registros novos nascem com `signature_version = v2` (default).
- Registros legados podem permanecer em `v1` durante a janela de 90 dias — o
  notifier emite o formato conforme a configuração do lojista.
- `v1` é removido ao fim da janela; todo lojista deve validar `v2`.

## Notas

- **Escopo:** este contrato cobre **somente** o envio (FoxCash → lojista). O
  recebimento (PSP → FoxCash) usa o `WebhookSignatureFilter` inbound, intacto.
- **Rate limit do receptor:** o receptor deve responder `429` para limitar; a
  política acima trata `429` como `4xx` (falha permanente — config do cliente).
- O webhook de **operador** (alertas de plataforma, ex.: `JOB_FAILED`) usa o
  mesmo formato v2 com o segredo do operador.
