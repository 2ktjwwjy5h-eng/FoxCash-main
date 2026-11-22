---
name: Mensagens automáticas e agendamento (backend)
status: audited
references:
  - "management/vision.md#V31"
  - "management/specs/48-stores-storefront/requirements.md"
---

# Spec 51 — Mensagens automáticas e agendamento (backend)

## O que entrega

As **mensagens da loja** — editáveis pelo vendedor, renderizadas pelo bot
(Spec 52):

1. **Mensagens por etapa da venda** (`store_messages`, event):
   - `welcome` — boas-vindas ao abrir o menu ("Olá 👋 Bem-vindo à Bella Premium.").
   - `awaiting_payment` — após criar a cobrança (junto ao QR).
   - `payment_received` — quando o `SALE_PAID` chega (antes da entrega).
   - `delivered` — após todas as ações de entrega (Spec 49).
   - `promo` — mensagem de promoção (exibida quando o vendedor quiser/botão).
   - `faq` — texto de dúvidas (botão `faq` da Spec 50).
2. **Mensagens programadas:** a vendedora agenda envios (ex.: promoção em
   massa) — `scheduled_at` (uma vez) ou `cron` (recorrente). O backend mantém o
   agendamento e o bot (Spec 52) consome/executa via endpoint de "mensagens
   programadas vencidas".

## Critérios de aceite

- **CRUD (portal):** `GET/POST /api/v1/portal/store/messages` (upsert por
  `event` — 1 mensagem por etapa), `PATCH /{id}` (text/active), `DELETE /{id}`
  (204). Validações: `event` enum, `text` 1–1000; 400 `invalid_argument`, 404
  `message_not_found`; privado por lojista.
- **Agendamento:** `POST /api/v1/portal/store/messages/schedule`
  `{text, scheduledAt | cron, audience: BUYERS}` — **nesta versão apenas
  `BUYERS`** (o backend resolve os destinatários: os `platform_user_id` dos
  compradores registrados nas entregas, Spec 49; `ALL` — enviar a todos os
  usuários da plataforma — fica como **evolução**, pois exige lista de usuários
  fora do backend). `GET /api/v1/foxcash/scheduled-messages?dueBefore=` (API key —
  devolve as programadas vencidas **com `recipients: [platformUserId…]`** para
  o bot enviar e marcar; `POST
  /api/v1/foxcash/scheduled-messages/{id}/sent` confirma o envio). Cron validado
  (6 campos).
- **Storefront:** mensagens por etapa incluídas no `GET /nyx/storefront`
  (Spec 48) — o bot renderiza `welcome`/`awaiting_payment`/`payment_received`/
  `delivered` no fluxo.
- **Regressão:** `mvn test` verde; E2E **22/22 + cenário** (upsert mensagens,
  agendar promo, bot buscar vencidas e confirmar).
- **Cross-cutting:** validação, rate limit, logs mascarados (texto pode conter
  link privado), atividade `MESSAGE_CREATED/UPDATED`, `MESSAGE_SCHEDULED`
  (Spec 41).

## Dependências

- `management/specs/48-stores-storefront/requirements.md` (loja raiz)
- `management/specs/50-botoes-menu/requirements.md` (botão `faq`)
- `management/specs/41-master-profiles-activity/requirements.md` (auditoria)
