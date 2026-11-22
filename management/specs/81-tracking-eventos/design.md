---
name: Tracking — perfis e disparo server-side de eventos (backend)
status: audited
---

# Spec 81 — Design: Tracking (perfis e disparo server-side)

## D1 — Conceito

O tracking é uma **camada de dispatch** sobre o funil da Spec 75: quando o
funil grava um evento (`lead`/`checkout_started`/`paid` por bot), o dispatcher
localiza os **perfis de tracking** vinculados ao bot (Spec 74) e publica **um
job de outbox por (perfil, evento)** (Spec 35). O dispatcher do outbox envia
para os provedores configurados (CAPI/TikTok/UTMify/Otimizey) com retry/
backoff; o resultado vai para `event_logs` (idempotente). Nada de fila nova.

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Tokens criptografados | `CryptoConverter` (padrão Spec 74); nunca em claro no response |
| Dispatch via outbox | Spec 35 (retry/backoff, OUTBOX_DEAD) — sem fila nova |
| Fonte dos eventos | funil da Spec 75 (`lead`/`checkout_started`/`paid`) |
| `user_data` hasheado | email/phone SHA-256 (CAPI); fbp/fbc repassados |
| Log idempotente | `event_logs` UNIQUE `(profile_id, event_id)` |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V52__tracking.sql`

Última migration conhecida: **V51** (Spec 79). Usar **V52** (verificar o
próximo número livre). **Também altera `bot_leads`** (UTM):

```sql
ALTER TABLE bot_leads ADD COLUMN utm_source   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_medium   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_campaign VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_content  VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN utm_term     VARCHAR(255);

CREATE TABLE tracking_profiles (
    id                               UUID PRIMARY KEY,
    merchant_id                      UUID NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    name                             VARCHAR(255) NOT NULL,
    status                           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                                     CHECK (status IN ('ACTIVE','INACTIVE')),
    facebook_pixel_id                VARCHAR(255),
    facebook_access_token_encrypted  TEXT,   -- CryptoConverter v1:
    tiktok_pixel_id                  VARCHAR(255),
    tiktok_access_token_encrypted    TEXT,
    utmify_token_encrypted           TEXT,
    otimizey_token_encrypted         TEXT,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tracking_profile_bots (
    profile_id  UUID NOT NULL REFERENCES tracking_profiles (id) ON DELETE CASCADE,
    bot_id      UUID NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    PRIMARY KEY (profile_id, bot_id)
);

CREATE TABLE event_logs (
    id                BIGSERIAL PRIMARY KEY,
    profile_id        UUID NOT NULL REFERENCES tracking_profiles (id) ON DELETE CASCADE,
    event_type        VARCHAR(32) NOT NULL,          -- LEAD/INITIATE_CHECKOUT/PURCHASE
    event_id          UUID NOT NULL,                 -- id do evento do funil (Spec 75)
    status            VARCHAR(16) NOT NULL CHECK (status IN ('OK','ERROR')),
    response_payload  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, event_id)
);

CREATE INDEX ix_tracking_profile_bots_bot ON tracking_profile_bots (bot_id);
CREATE INDEX ix_event_logs_profile ON event_logs (profile_id, created_at DESC);
```

### D2.2 Camada JPA

- `TrackingProfileJpaEntity` (colunas `*_encrypted` com `CryptoConverter`) +
  `TrackingProfileJpaRepository` (`findByMerchantId`,
  `findByMerchantIdAndStatus`) + mapper.
- `TrackingProfileBotJpaEntity` + `TrackingProfileBotJpaRepository`
  (`findByBotId`, `findByProfileId`).
- `EventLogJpaEntity` + `EventLogJpaRepository`
  (`findByProfileIdAndEventId` — idempotência) + mapper.
- Portas `TrackingProfileRepository`/`TrackingProfileBotRepository`/
  `EventLogRepository` + adapters JPA.

## D3 — Domínio

- `TrackingProfile` (aggregate): `create`/`update` (validação por provedor:
  pixel + token juntos; nome obrigatório; 1–100 chars), `activate`/
  `deactivate`/`duplicate`/`remove`; `tokenMask()` (últimos 4).
- `TrackingServiceType { FACEBOOK, TIKTOK, UTMIFY, OTIMIZEY }` (badges do
  painel — Spec 82).
- `EventLog` (value object imutável): `profileId`, `eventType`, `eventId`,
  `status`, `responsePayload`.

## D4 — Captura de UTM

- O bot (Spec 52) já conhece o link de entrada (Telegram: `start` payload/
  texto do update; Discord: origem da interação). Na integração da Spec 74:
  - Telegram: o update pode carregar o `start` com payload
    (`?utm_source=fb&utm_campaign=...`); o webhook da Spec 74 (D5.3) extrai e
    passa para `recordLead` (Spec 75) com as UTMs.
  - Discord: a origem do convite/link carrega as UTMs na primeira interação.
- `BotLeadService.recordLead` (Spec 75) ganha parâmetro opcional de UTMs —
  **primeiro valor vence** (idempotente; replay do update não sobrescreve).
- Normalização: `utm_*` com máximo de 255 chars, sanitizadas (sem HTML).

## D5 — Dispatch (funil → outbox → provedores)

### D5.1 Gatilhos (na mesma transação do evento do funil)

- `lead` → `TrackingDispatchService.onLead(botId, platformUserId, utms)`
- `checkout_started` → `onCheckout(botId, transactionId, amount)`
- `paid` → `onPurchase(botId, transactionId, amount)`

Cada gatilho: busca perfis `ACTIVE` vinculados ao bot
(`tracking_profile_bots`); para cada perfil, **publica um job de outbox**
(Spec 35) com o payload do evento — a publicação é na mesma transação (padrão
outbox: at-least-once com dedup).

### D5.2 Processamento (dispatcher da Spec 35)

Para cada job: resolve o provedor (perfil × tipo configurado) e envia via o
adapter correspondente (D6). Sucesso → `event_logs` OK; falha → retry/backoff
do outbox (max 5, `OUTBOX_DEAD` → alerta do operador, Spec 54) e `event_logs`
ERROR com `response_payload` (truncado). Idempotência: `event_logs` UNIQUE
`(profile_id, event_id)` — replay não duplica a métrica.

## D6 — Adapters (porta `TrackingProviderClient`)

`interface TrackingProviderClient { send(TrackingEvent e): ProviderResult }`
com um adapter por provedor + mock de contrato (padrão
`PayoutProviderContractTest`):

- **FacebookCapiClient**: `POST
  https://graph.facebook.com/v19.0/{pixel_id}/events` — headers
  `Authorization: Bearer {token}`; body: `data[]` com `event_name`,
  `event_time` (Unix), `user_data` (`em`/`ph` SHA-256 lowercase hex, `fbp`/
  `fbc` se presentes), `custom_data { value, currency: "BRL" }`. Resposta não
  `200`/`error` → `ProviderResult.failure`.
- **TikTokEventsClient**: `POST
  https://business-api.tiktok.com/open_api/v1.3/pixel/track/` — `Access-Token`
  header; body: `pixel_code`, `event`, `event_time`, `user`, `properties`
  (value/currency), `context` (UTMs). 
- **UtmifyClient / OtimizeyClient**: POST no endpoint da ferramenta com o
  token (header `Authorization` ou body, conforme config da ferramenta —
  decisão de implementação documentada) + payload do pedido + UTMs.
- **Timeouts e retry de rede** (padrão dos adapters de PSP, Spec 11/38);
  validação SSRF-safe de URL (Spec 35) para webhooks.

## D7 — REST (portal)

| Método | Rota | Auth | Observação |
|---|---|---|---|
| POST | `/api/v1/portal/tracking` | ROLE_MERCHANT_PORTAL | cria perfil (+ vínculo de bots) |
| GET | `/api/v1/portal/tracking` | ROLE_MERCHANT_PORTAL | lista (com badges e resumo do log) |
| GET | `/api/v1/portal/tracking/{id}` | ROLE_MERCHANT_PORTAL | detalhe (sem tokens em claro) |
| PUT | `/api/v1/portal/tracking/{id}` | ROLE_MERCHANT_PORTAL | atualiza (+ vínculo de bots) |
| POST | `/api/v1/portal/tracking/{id}/activate` · `/deactivate` | ROLE_MERCHANT_PORTAL | status |
| POST | `/api/v1/portal/tracking/{id}/duplicate` | ROLE_MERCHANT_PORTAL | novo perfil (ids novos) |
| POST | `/api/v1/portal/tracking/{id}/sync` | ROLE_MERCHANT_PORTAL | teste por provedor (D8) |
| DELETE | `/api/v1/portal/tracking/{id}` | ROLE_MERCHANT_PORTAL | remove (cascade vínculos/logs) |
| GET | `/api/v1/portal/tracking/{id}/events` | ROLE_MERCHANT_PORTAL | log de eventos (paginado) |

Convenções: cross-lojista → 404; tokens nunca em resposta (máscara);
auditoria em toda escrita (D9).

## D8 — Sincronizar (teste de conexão)

`POST /{id}/sync` — para cada provedor configurado:
- Facebook: `GET https://graph.facebook.com/v19.0/{pixel_id}?access_token=`
  (valida pixel + token).
- TikTok: chamada de validação do token/pixel (ou POST de teste no
  `pixel/track` com `test_event_code`).
- UTMify/Otimizey: POST de ping no webhook (sem payload de venda).
Retorna `[{ service, ok, message }]`; falha de um não impede os demais.

## D9 — Auditoria e métricas

- `PlatformAction` += `TRACKING_PROFILE_CREATED`, `TRACKING_PROFILE_UPDATED`,
  `TRACKING_PROFILE_ACTIVATED`, `TRACKING_PROFILE_DEACTIVATED`,
  `TRACKING_PROFILE_DUPLICATED`, `TRACKING_PROFILE_DELETED` — seção `// Bots
  (Spec 74)` (o tracking pertence ao domínio de bots) — mesma transação
  (Spec 41).
- Métricas `nyx_tracking_*` (Spec 53): `enviados`, `ok`, `erros`,
  `latencia_provedor` (por provedor).

## D10 — Configuração

```yaml
foxcash:
  tracking:
    dispatch-enabled: ${NYX_TRACKING_DISPATCH_ENABLED:true}
    capi-api-version: v19.0
    tiktok-base-url: https://business-api.tiktok.com/open_api/v1.3
    event-log-response-max: 500   # truncamento do response_payload
```

`TrackingProperties` + binding contra o yml real (padrão Spec 68/L16).
