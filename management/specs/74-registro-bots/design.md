---
name: Registro central de bots de venda (backend)
status: audited
---

# Spec 74 — Design: Registro central de bots de venda

## D1 — Conceito

O registro central é uma **camada de configuração e ciclo de vida** por cima
do motor de venda existente (Spec 52). O renderizador continua stateless e
config-driven (lê `GET /nyx/storefront`, entrega via API); o que muda é que a
**identidade e o estado do bot passam a viver no PostgreSQL** (`store_bots`),
com integração real por plataforma:

- **Telegram**: o painel grava o token (criptografado) → valida via `getMe` →
  registra `setWebhook` com URL `https://<host>/webhooks/telegram/{id}/{secret}`
  e `secret_token` → o endpoint público recebe updates, valida o segredo e
  encaminha o evento ao renderizador (outbox, Spec 35).
- **Discord**: o painel grava o token + Application ID → um **gerenciador de
  conexão** (gateway) conecta com reconexão/backoff → o renderizador recebe as
  interações.

Nada de novo mecanismo de criptografia, webhook ou fila: **reuso** de
`CryptoConverter` (AES-256/GCM), do padrão de hash para lookup
(`token_hash`/`webhook_secret_hash`, mesmo padrão do `document_hash`), do
outbox da Spec 35, da auditoria da Spec 41 e do RBAC da Spec 69.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Registrar bots por lojista | `store_bots.merchant_id`; CRUD no portal escopado ao lojista (IDOR-safe) |
| Espelho no Master Console | `GET /api/v1/master/bots` read-only, RBAC Spec 69 (toda escrita fica no portal) |
| Criador de bot próprio por plataforma | Specs 77/78 (wizards) consomem esta camada — fora desta spec |
| Mesma ideia, mais robusto | Token criptografado, `secret_token` aleatório, máquina de estados, soft-delete, auditoria |
| Renderizador não muda | Spec 52 permanece; só ganha a possibilidade de ser registrado/gerenciado |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V49__store_bots.sql`

Última migration atual: **V48** (Spec 70). Usar **V49** (verificar o próximo
número livre em `src/main/resources/db/migration` — nunca colidir).

```sql
CREATE TABLE store_bots (
    id                   UUID PRIMARY KEY,
    merchant_id          UUID        NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    platform             VARCHAR(16) NOT NULL CHECK (platform IN ('TELEGRAM', 'DISCORD')),
    token_encrypted      TEXT        NOT NULL,                -- CryptoConverter v1: (AES-256/GCM)
    token_hash           VARCHAR(64) NOT NULL UNIQUE,         -- SHA-256 hex (lookup, IV aleatório)
    webhook_secret_hash  VARCHAR(64) NOT NULL UNIQUE,         -- SHA-256 hex do secret_token/URL
    display_name         VARCHAR(255) NOT NULL,
    username             VARCHAR(255),                        -- do getMe (Telegram) / @username (Discord)
    application_id       VARCHAR(64),                         -- Discord: Application ID (opcional)
    status               VARCHAR(16) NOT NULL DEFAULT 'REGISTERING'
                         CHECK (status IN ('REGISTERING','ACTIVE','PAUSED','ERROR')),
    error_message        VARCHAR(500),
    last_health_check_at TIMESTAMPTZ,
    deleted_at           TIMESTAMPTZ,                         -- soft-delete
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_store_bots_merchant ON store_bots (merchant_id, platform) WHERE deleted_at IS NULL;
```

Notas:
- `platform` CHECK em **UPPERCASE** (`('TELEGRAM','DISCORD')`) — o JPA mapeia o enum
  com `@Enumerated(EnumType.STRING)` (nome do enum em maiúsculas), mesmo padrão
  do `status` (V43). Valores em lowercase quebrariam o INSERT (verificado ao
  vivo durante a implementação).
- `token_hash`/`webhook_secret_hash` em `VARCHAR(64)` — SHA-256 hex (64 chars);
  mesmo padrão do `document_hash`/`api_key_hash` (V6). Lookup por igualdade num
  campo com hash determinístico — o ciphertext é não-determinístico por IV.
- Soft-delete: `deleted_at IS NOT NULL` = removido; a linha permanece para
  auditoria/reuso do histórico.

### D2.2 Camada JPA

- `StoreBotJpaEntity` + `StoreBotJpaRepository` (`findByMerchantIdAndDeletedAtIsNull`,
  `findByTokenHash`, `findByWebhookSecretHash`) + `StoreBotMapper`.
- Coluna `token_encrypted` mapeada com o `CryptoConverter` (atributo converter).
- Porta `StoreBotRepository` (hexagonal) + adapter JPA — padrão das demais
  portas do projeto.

## D3 — Domínio

`StoreBot` (aggregate por `merchant_id`):

- Enums: `BotPlatform { TELEGRAM, DISCORD }`, `BotStatus
  { REGISTERING, ACTIVE, PAUSED, ERROR }`.
- Transições de estado (métodos que lançam `InvalidBotStateException` → 409
  `invalid_bot_state`): `activate()`, `pause()`, `resume()`, `markError(msg)`,
  `remove()` (soft).
- `token` nunca exposto pelo aggregate: expõe `tokenMask()` (últimos 4) e
  `tokenHash()` para lookup; descriptografar só na camada de integração (porta
  `BotTokenDecryptor`), nunca em resposta HTTP.

## D4 — Serviços

- `StoreBotCommandService` (portal): `register(merchantId, platform, token)`,
  `update(merchantId, id, displayName)`, `pause/resume/remove` — cada ação
  dispara a integração (D5/D6) e a auditoria (D9). Concorrência no `register`:
  serialização pela **UNIQUE de `token_hash`** (o `SELECT ... FOR UPDATE` da
  Spec 49 não se aplica a INSERT de linha nova — não há linha a travar); o
  serviço checa `findByTokenHash` (409 `bot_already_exists`) **e** converte
  `DataIntegrityViolationException` da UNIQUE em 409 no replay concorrente
  (replay executa 1×, o outro recebe conflito limpo — nunca 500).
- `StoreBotQueryService` (portal + master): listagem com filtro por plataforma
  e status; resposta nunca inclui o token.
- `StoreBotIntegrationService`: orquestra a integração por plataforma —
  `integrate(bot)` (Telegram: getMe + setWebhook; Discord: connect),
  `disintegrate(bot)` (Telegram: deleteWebhook; Discord: destroy),
  `reconcile()` (job ShedLock + `JobExecutionService`, Spec 53: registros
  `REGISTERING` presos > N min → `ERROR`; `ACTIVE` sem `last_health_check_at`
  recente → re-verifica).

## D5 — Integração Telegram

### D5.1 Cliente da API do Telegram

`TelegramBotApiClient` (REST, `https://api.telegram.org/bot{token}/{method}`):
`getMe`, `setWebhook(url, secret_token)`, `deleteWebhook`, `setMyDescription`,
`setMyShortDescription`, `setMyCommands`, `getWebhookInfo`. Timeouts e retry
de rede (padrão dos adapters de PSP, Spec 11/38). **Mock em teste** via porta
`TelegramBotApi` (adapter real + mock de contrato — padrão
`PayoutProviderContractTest`).

### D5.2 Registro de webhook

URL: `https://<foxcash.webhook.public-base-url>/webhooks/telegram/{id}/{secret}`
onde `{secret}` = 32 bytes aleatórios (hex). A URL **não** usa o id do bot como
segredo. `secret_token` = o mesmo valor (Telegram envia no header
`X-Telegram-Bot-Api-Secret-Token`). `webhook_secret_hash` = SHA-256 do secret.

### D5.3 Endpoint público de updates

`POST /webhooks/telegram/{id}/{secret}` (público, fora do SecurityConfig
autenticado — mesmo tratamento dos `/webhooks/pix` e `/webhooks/payouts`
(`PixWebhookController`/`PayoutWebhookController` em
`infrastructure/inbound/webhook/`, `WebhookSignatureFilter`), mas com segredo
na URL):

1. Lookup por `webhook_secret_hash` (constant-time hash compare) → não achou →
   `404` (sem processar).
2. Se `X-Telegram-Bot-Api-Secret-Token` presente → comparar (constant-time);
   inválido → `404`.
3. Bot `PAUSED`/`ERROR`/`deleted` → descarta com log (nunca encaminha).
4. Publica o evento no **outbox** (Spec 35) para o renderizador consumir
   (mesmo padrão de eventos de venda → webhook do bot).
5. Rate limit por IP (Spec 60) e body cap (Spec 63) herdados.

## D6 — Integração Discord

`DiscordBotManager` — gerenciador em memória (por instância de app) que mantém
conexões de gateway ativas por `store_bot_id`:

- `connect(bot)`: cria cliente (discord.js) com **reconexão/backoff
  exponencial** e heartbeat; atualiza `last_health_check_at`.
- `disconnect(botId)`: `client.destroy()` + remove da memória.
- Restart do app: estado em memória recria a partir do banco (job `reconcile`)
  — mesma decisão stateless da Spec 52 (o **PostgreSQL é a fonte de verdade**;
  conexão é estado efêmero). Sem pool multi-instância: documentar que o
  gerenciador é por instância (1 réplica) e que a Spec 62/HA não cobre isso
  (decisão registrada; o gateway do Discord exige 1 conexão por bot).
- Comandos slash: `PUT /applications/{app_id}/commands` via REST (Bearer token)
  com retry.

## D7 — REST (portal + master)

| Método | Rota | Auth | Observação |
|---|---|---|---|
| POST | `/api/v1/portal/bots` | ROLE_MERCHANT_PORTAL | cria (token no body) → integra → ACTIVE/ERROR |
| GET | `/api/v1/portal/bots` | ROLE_MERCHANT_PORTAL | lista do lojista (filtro platform/status) |
| GET | `/api/v1/portal/bots/{id}` | ROLE_MERCHANT_PORTAL | detalhe (sem token) |
| PUT | `/api/v1/portal/bots/{id}` | ROLE_MERCHANT_PORTAL | edita display_name |
| POST | `/api/v1/portal/bots/{id}/pause` | ROLE_MERCHANT_PORTAL | → PAUSED (+ desintegra) |
| POST | `/api/v1/portal/bots/{id}/resume` | ROLE_MERCHANT_PORTAL | → ACTIVE (+ integra) |
| DELETE | `/api/v1/portal/bots/{id}` | ROLE_MERCHANT_PORTAL | soft-delete (+ desintegra) |
| GET | `/api/v1/master/bots` | ROLE_MASTER | espelho read-only (todos os lojistas) |
| GET | `/api/v1/master/bots/{id}` | ROLE_MASTER | espelho read-only |

Convenções: respostas sem token/secret; erros `409 invalid_bot_state`,
`400` validação, `404` cross-lojista (IDOR-safe — padrão Specs 24/31); auditoria
em toda escrita.

## D8 — Segurança

- **Token em repouso**: `CryptoConverter` (AES-256/GCM, `v1:`) — reuso, sem
  mecanismo novo. Chave: `foxcash.crypto.secret` (NYX_CRYPTO_SECRET em prod).
- **Lookup**: `token_hash`/`webhook_secret_hash` (SHA-256, hex) — nunca buscar
  pelo ciphertext.
- **Webhook público**: segredo na URL + `secret_token` (constant-time);
  `404` silencioso para segredo inválido; descarte em PAUSED/ERROR; rate limit
  por IP (Spec 60); body cap (Spec 63); **nunca logar** token/secret.
- **IDOR**: toda query do portal filtra por `merchant_id` do principal;
  cross-lojista → `404` (não 403, não revela existência).
- **RBAC master** (Spec 69): espelho read-only exige permissão de operação
  (OPERATOR — leitura).

## D9 — Auditoria e jobs

- `PlatformAction` += `BOT_REGISTERED`, `BOT_UPDATED`, `BOT_PAUSED`,
  `BOT_RESUMED`, `BOT_REMOVED`, `BOT_INTEGRATION_ERROR` — **nova seção
  `// Bots (Spec 74)`** no enum (o enum usa seções por comentário: `// Master`,
  `// Lojista / portal`, `// Operações financeiras`, `// Operacional /
  auditoria`) — mesma transação da escrita (Spec 41).
- Job `foxcash-bot-reconcile` (ShedLock + `JobExecutionService`, Spec 53;
  cron configurável, kill-switch `foxcash.bots.reconcile-enabled`):
  `REGISTERING` preso → `ERROR`; `ACTIVE` sem health recente → re-verifica
  integração; `ERROR` com token válido → tenta re-integrar (log + métrica
  `nyx_bot_*`, padrão Spec 53).

## D10 — Configuração

`application.yml` (bloco `foxcash.bots`):

```yaml
foxcash:
  bots:
    webhook-public-base-url: ${NYX_BOTS_WEBHOOK_BASE_URL:}   # vazio = registra webhook com URL de dev
    reconcile-enabled: ${NYX_BOTS_RECONCILE_ENABLED:true}
    discord-gateway-enabled: ${NYX_BOTS_DISCORD_GATEWAY_ENABLED:false}  # off em dev/test
```

Contexto das propriedades bindado a `BotProperties` + teste de binding contra o
yml real (padrão Spec 68/L16).
