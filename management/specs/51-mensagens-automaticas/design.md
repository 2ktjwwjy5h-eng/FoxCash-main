# Spec 51 — Design: Mensagens automáticas e agendamento

## Decisões de arquitetura

### 1. Modelo (migration V36 — parte 4)

- `store_messages`: `id UUID PK`, `store_id FK`, `event VARCHAR(40)` (UNIQUE por
  loja: WELCOME/AWAITING_PAYMENT/PAYMENT_RECEIVED/DELIVERED/PROMO/FAQ),
  `text TEXT NOT NULL`, `active BOOLEAN DEFAULT TRUE`, `created_at`,
  `updated_at`.
- `store_scheduled_messages`: `id UUID PK`, `store_id FK`, `text TEXT`,
  `scheduled_at TIMESTAMP` (NULL se `cron`), `cron VARCHAR(100)` (NULL se
  one-shot; 6 campos), `audience VARCHAR(20)` (BUYERS — nesta versão; ALL fica
  como evolução), `status` (SCHEDULED/SENT), `sent_at`, `created_at`. Índice
  `(store_id, status, scheduled_at)`.

### 2. Domínio

- `StoreMessage`: create/restore/update; validação (event enum, text 1–1000).
- `ScheduledMessage`: create/restore/markSent(now); validação cron (6 campos,
  Quartz-compatível) OU scheduled_at.

### 3. Serviços/controllers

- `StoreMessageService` (portal, ROLE_MERCHANT_PORTAL):
  - `GET /api/v1/portal/store/messages` (todas da loja).
  - `POST .../messages` (upsert por event → 201/200), `PATCH /{id}`,
    `DELETE /{id}` (204).
  - `POST .../messages/schedule` (cria `store_scheduled_messages`).
- `ScheduledMessageQueryService` (nyx, API key):
  - `GET /api/v1/foxcash/scheduled-messages?dueBefore=<ISO>` → vencidas
    (SCHEDULED e scheduled_at ≤ dueBefore, ou cron vencido) do lojista, **com
    `recipients`** = lista de `platform_user_id` dos compradores (entregas
    `DELIVERED` da loja — Spec 49; deduplicados).
  - `POST /api/v1/foxcash/scheduled-messages/{id}/sent` → marca SENT (204,
    idempotente).
- Storefront (Spec 48) inclui `messages{}` por event (ativas) — o bot usa
  `welcome`/`awaiting_payment`/`payment_received`/`delivered` no fluxo e
  `faq`/`promo` nos botões (Spec 50).

### 4. Entrega das mensagens por etapa (Spec 52)

O bot renderiza `awaiting_payment` junto ao QR, `payment_received` no SALE_PAID
e `delivered` após a última ação (Spec 49). Textos podem conter variáveis
`{nome}`, `{plano}` (template simples `${var}` substituído pelo bot — definido
na Spec 52).

## Arquivos

- `domain/model/store/StoreMessage.java`, `domain/model/store/ScheduledMessage.java`
- Portas: `StoreMessageRepository`, `ScheduledMessageRepository`
- Serviços: `StoreMessageService`, `ScheduledMessageQueryService`
- Persistência + controllers (portal e nyx) + DTOs
- `PlatformAction` += MESSAGE_CREATED/UPDATED/SCHEDULED
- Testes: domínio, serviços, controllers, E2E
