# Spec 51 — Task breakdown

Status: `audited` (2026-08-06). **Ciclo SDD completo — feature LIVE.**

## Auditoria (`/spec-audit`, 2026-08-06)

- [x] **20/20 checks ao vivo OK** contra o app dev: upsert por event (201/200,
      nunca duplica), validações 400 (event inválido, text vazio, cron inválido,
      XOR scheduledAt+cron), one-shot + cron 201, bot `GET
      /nyx/scheduled-messages?dueBefore=` com `recipients[]`, `POST /sent` 204
      idempotente (some das vencidas), storefront `messages{}`, PATCH/DELETE.
- [x] Auditoria Spec 41 (`MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED` na mesma
      transação, texto nunca em claro) confirmada nos testes unitários. `mvn
      test` 675/0/0 e E2E 26/26 sem regressão. **0 achados.**

## Implementação

- [x] Migration **V40**: `store_messages` (unique `(store_id, event)`) e
      `store_scheduled_messages` (índice `(store_id, status, scheduled_at)`).
- [x] `StoreMessage`/`ScheduledMessage` (validação event/text 1–1000/cron 6
      campos Quartz) + portas `StoreMessageRepository`/`ScheduledMessageRepository`.
- [x] `StoreMessageService` + `PortalStoreMessageController`
      (GET/POST upsert 201/200/PATCH/DELETE, POST schedule).
- [x] `ScheduledMessageQueryService` + `FoxScheduledMessageController`
      (`GET /api/v1/foxcash/scheduled-messages?dueBefore=`, `POST /{id}/sent`).
- [x] `StorefrontService` (Spec 48) inclui `messages{event: text}` ativas.
- [x] `PlatformAction` += MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED; handler
      404 `message_not_found`; recipients via
      `PlanDeliveryRepository.findDistinctDeliveredPlatformUserIds`.
- [x] Testes: domínio, serviços, controllers, storefront; E2E cenário 26.

## Verificação

- [x] `mvn test` verde (**675/0/0** — 52 novos) e E2E **26/26** sem regressão.

## Verificação (`/spec-verify`, 2026-08-05)

- [x] Consistência verificada: mensagens por etapa (unique `(store_id, event)`) +
      agendamento; bot renderiza e confirma envio.
- [x] Achados aplicados: `audience` fechado em **BUYERS** nesta versão (o bot é
      stateless e não tem lista de usuários; `ALL` fica como evolução) — o
      endpoint devolve `recipients` (platform_user_id dos compradores, Spec 49).
      Status → `verified`.
