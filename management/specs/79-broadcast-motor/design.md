---
name: Broadcast — motor de disparo em massa (backend)
status: audited
---

# Spec 79 — Design: Broadcast (motor de disparo em massa)

## D1 — Conceito

O broadcast é uma **nova capacidade de transmissão** que reusa o mecanismo de
envio existente (Spec 51/52): o backend **orquestra e persiste**, o bot
**envia com rate limit nativo** e **reporta o resultado por destinatário**.
Nada de fila nova (sem BullMQ — stack Java/Spring); o "fila" é o padrão já
provado: o bot consulta pendências (`due`) via API e confirma/envia logs.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Sem BullMQ | envio no bot (Spec 52) com rate limit nativo das libs; backend orquestrador |
| Log por destinatário | `broadcast_logs` (SUCCESS/BLOCKED/FAILED), reportado pelo bot via API |
| Destino = canal/grupo | `target_chat_id` resolvido via registro da Spec 74 (chats onde o bot é admin) |
| 4 modos | NOW / SCHEDULED (reuso Spec 51) / WELCOME (evento de entrada) / REPOST (intervalo) |
| Mídia via CDN | upload → `media_url` + `media_file_id`; disparo sem re-upload |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V51__broadcast.sql`

Última migration conhecida: **V50** (Spec 75). Usar **V51** (verificar o
próximo número livre).

```sql
CREATE TABLE broadcasts (
    id                UUID PRIMARY KEY,
    store_bot_id      UUID        NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    mode              VARCHAR(16) NOT NULL CHECK (mode IN ('NOW','SCHEDULED','WELCOME','REPOST')),
    target_chat_id    VARCHAR(255),
    content           TEXT        NOT NULL,
    media_url         VARCHAR(500),
    media_type        VARCHAR(16) CHECK (media_type IN ('image','video','audio')),
    media_file_id     VARCHAR(255),
    buttons_json      JSONB,
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    scheduled_at      TIMESTAMPTZ,
    interval_seconds  INT,                                  -- REPOST (>= 3600)
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE broadcast_logs (
    id                BIGSERIAL PRIMARY KEY,
    broadcast_id      UUID        NOT NULL REFERENCES broadcasts (id) ON DELETE CASCADE,
    recipient_id      VARCHAR(255) NOT NULL,
    status            VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS','BLOCKED','FAILED')),
    error_message     VARCHAR(500),
    sent_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (broadcast_id, recipient_id)
);

CREATE INDEX ix_broadcasts_status_due ON broadcasts (status, scheduled_at);
CREATE INDEX ix_broadcast_logs_broadcast ON broadcast_logs (broadcast_id);
```

### D2.2 Camada JPA

- `BroadcastJpaEntity` (`buttons_json` via JSONB mapeado como `String`/JSON) +
  `BroadcastJpaRepository` (`findByStatusAndScheduledAtBeforeOrNull`,
  `findByModeAndStatus`) + mapper.
- `BroadcastLogJpaEntity` + `BroadcastLogJpaRepository`
  (`findByBroadcastIdAndRecipientId` — idempotência) + mapper.
- Portas `BroadcastRepository`/`BroadcastLogRepository` (hexagonal) +
  adapters JPA.

## D3 — Domínio

- `BroadcastMode { NOW, SCHEDULED, WELCOME, REPOST }`,
  `BroadcastStatus { PENDING, PROCESSING, COMPLETED, FAILED }`,
  `BroadcastLogStatus { SUCCESS, BLOCKED, FAILED }`.
- `Broadcast` (aggregate): `create` (validações: `target_chat_id` obrigatório
  para NOW/SCHEDULED/REPOST; `scheduled_at > now` p/ SCHEDULED;
  `interval_seconds >= 3600` p/ REPOST; conteúdo ou mídia), máquina de
  estados (`markProcessing`/`complete`/`fail(msg)`), `schedule`, `cancel`
  (apenas PENDING/SCHEDULED → `CANCELLED` não é status do schema externo —
  usar `FAILED` com `error_message='cancelled'`? **decisão:** DELETE físico de
  PENDING é permitido e auditado; SCHEDULED idem; PROCESSING não pode
  cancelar → 409).
- `BroadcastButtons` (value object): parse/validação do `buttons_json`
  (limites por plataforma, D8).

## D4 — Serviços

- `BroadcastCommandService` (portal): `create` (valida + persiste; NOW →
  `PENDING`; SCHEDULED → `PENDING` com `scheduled_at`; REPOST → `PENDING` +
  agenda job), `cancel`, `uploadMedia`. Auditoria (D9) na mesma transação.
- `BroadcastQueryService` (portal): listagem (filtro modo/status), detalhe +
  logs (paginação).
- `BroadcastDispatchService` (API do bot): `due(dueBefore)` — broadcasts
  `PENDING` com `scheduled_at <= dueBefore` OU `NOW` sem agendamento;
  `markSent(id)` (→ COMPLETED se todos os logs OK; FAILED se houve
  BLOCKED/FAILED? **decisão:** COMPLETED se ≥1 SUCCESS; FAILED se nenhum;
  logs individuais sempre preservados); `welcomeTrigger(botId, chatId,
  userId)` (D6); `reportLog(broadcastId, recipientId, status, error)` —
  idempotente (UNIQUE).

## D5 — Destino (chats do bot)

`GET /api/v1/portal/bots/{id}/chats` — via integração da Spec 74:

- **Telegram**: `getChat`/lista de chats do bot (canais/grupos onde é
  admin) — adapter `TelegramBotApi` (Spec 74 D5.1) estendido com
  `getUpdates`/`getChat` conforme necessário (aditivo documentado no task).
- **Discord**: guilds/canais do bot no gerenciador de conexão (Spec 74 D6).

Retorno: `[{ id, title, type (channel/group/guild), permissions }]`. Cache
curto (TTL ~60s) para não martelar a API da plataforma.

## D6 — Modo WELCOME

- O bot detecta entrada de membro (`chat_member` no Telegram via webhook da
  Spec 74; `guildMemberAdd` no Discord) e chama
  `POST /api/v1/foxcash/broadcasts/welcome-trigger {botId, chatId, userId}`.
- O backend devolve a mensagem `WELCOME` **ativa para aquele chat** (o
  broadcast WELCOME mais recente com `target_chat_id` = chat) + mídia/botões;
  sem mensagem → `204` (no-op).
- **Idempotência**: chave `(chatId, userId)` na tabela
  `broadcast_welcome_sent` (novo, na migration) — evento duplicado do
  Telegram não envia 2×.

## D7 — Mídia CDN

`POST /api/v1/portal/broadcasts/media` (multipart):

1. Valida `Content-Type`/tamanho (imagem ≤10MB; vídeo/áudio ≤50MB) — 400.
2. **Telegram**: envia para o canal CDN do bot (`sendPhoto`/`sendVideo`/
   `sendAudio`) e guarda o `file_id` retornado (reuso nos disparos — sem
   re-upload). **Discord**: guarda a URL do attachment/upload (CDN do
   Discord).
3. Retorna `{ media_url, media_file_id, media_type }` para o editor (Spec 80).

Estratégia: `file_id` do Telegram é o caminho preferido (evita re-upload);
URL de CDN como fallback. O binário **não** fica no PostgreSQL.

## D8 — Validação de conteúdo e botões

- **Rich-text**: validação por plataforma — Telegram: HTML (whitelist de
  tags `b/strong/i/em/u/s/code/pre/blockquote/tg-spoiler` — **sem HTML
  arbitrário**, reuso da sanitização da Spec 63) ou Markdown; Discord:
  Markdown. Tags `{primeiro_nome}/{username}/{data}` resolvidas pelo
  `renderTemplate` do bot (Spec 52).
- **Botões** (`buttons_json`): `{ rows: [[{label, url?|callback?}]] }`;
  limites: Telegram ≤100 botões, ≤8/linha; Discord ≤5/linha; botão `link`
  exige `url` válida (validação SSRF-safe da Spec 35); `callback` exige
  `callback` no formato `bot:<comando>` (resolvido pelo bot).

## D9 — REST (portal) e API do bot

| Método | Rota | Auth | Observação |
|---|---|---|---|
| POST | `/api/v1/portal/broadcasts` | ROLE_MERCHANT_PORTAL | cria (modo/destino/conteúdo/mídia/botões) |
| GET | `/api/v1/portal/broadcasts` | ROLE_MERCHANT_PORTAL | lista (filtro modo/status) |
| GET | `/api/v1/portal/broadcasts/{id}` | ROLE_MERCHANT_PORTAL | detalhe + logs |
| DELETE | `/api/v1/portal/broadcasts/{id}` | ROLE_MERCHANT_PORTAL | cancela PENDING/SCHEDULED (409 se PROCESSING) |
| POST | `/api/v1/portal/broadcasts/media` | ROLE_MERCHANT_PORTAL | upload mídia (D7) |
| GET | `/api/v1/portal/bots/{id}/chats` | ROLE_MERCHANT_PORTAL | destino dinâmico (D5) |
| GET | `/api/v1/foxcash/broadcasts/due?dueBefore=` | API key (Spec 12) | pendências p/ bot (padrão Spec 51) |
| POST | `/api/v1/foxcash/broadcasts/{id}/sent` | API key | confirma envio (→ COMPLETED) |
| POST | `/api/v1/foxcash/broadcasts/{id}/logs` | API key | log por destinatário (idempotente) |
| POST | `/api/v1/foxcash/broadcasts/welcome-trigger` | API key | evento de entrada (D6) |

## D10 — Jobs e auditoria

- `foxcash-broadcast-due` (ShedLock + `JobExecutionService`, Spec 53; cron
  curto, ex. 30s; kill-switch `foxcash.broadcast.due-enabled`): nada a fazer
  além de marcar vencidos como PROCESSING **quando** o bot não estiver
  ativo? **decisão:** o job é para REPOST — re-agenda `scheduled_at = now +
  interval` e volta a `PENDING`; o NOW/SCHEDULED vira PROCESSING quando o bot
  consulta `due` (pull — sem job para isso, padrão Spec 51).
- `PlatformAction` += `BROADCAST_CREATED`, `BROADCAST_SCHEDULED`,
  `BROADCAST_CANCELLED` — **na seção `// Bots (Spec 74)`** do enum (Spec 74
  D9 define a seção) — na mesma transação da escrita (Spec 41).
- Métricas `nyx_broadcast_*` (Spec 53): enfileirados, enviados, falhas,
  duração.

## D11 — Configuração

```yaml
foxcash:
  broadcast:
    due-pull-enabled: ${NYX_BROADCAST_DUE_PULL_ENABLED:true}
    repost-enabled: ${NYX_BROADCAST_REPOST_ENABLED:true}
    media-max-image: 10485760      # 10MB
    media-max-video-audio: 52428800  # 50MB
```

`BroadcastProperties` + binding contra o yml real (padrão Spec 68/L16).
