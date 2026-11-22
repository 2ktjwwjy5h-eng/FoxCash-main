---
name: CRM — base de clientes e chat em tempo real (backend)
status: audited
---

# Spec 83 — Design: CRM (base de clientes e Live Chat)

## D1 — Conceito

O CRM é uma **camada de visão e conversa** sobre os leads da Spec 75 e a
integração da Spec 74:

- **Status** é **derivado** do funil (Spec 75) — nada manual.
- **Chat** é uma ponte: envio do painel via REST (mesma camada de envio dos
  bots, Spec 74), recebimento via webhook/manager, persistência em
  `chat_messages` e **push SSE** para o painel.
- **Tempo real** sem Socket.io: **SSE + Redis pub/sub** (fan-out entre
  instâncias, fail-open ADR-010).

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| SSE em vez de Socket.io | `SseEmitter` (zero dependência) + Redis pub/sub; sem sticky sessions |
| Status automático | derivado do funil (Spec 75) na mesma transação |
| Enviar = REST | auditável (Spec 41); reusa integração da Spec 74 |
| Bloqueio auto | Telegram 403 "bot was blocked" → `BLOQUEADO` |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V53__crm.sql`

Última migration conhecida: **V52** (Spec 81). Usar **V53** (verificar o
próximo número livre).

```sql
ALTER TABLE bot_leads ADD COLUMN name       VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN username   VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN email      VARCHAR(255);
ALTER TABLE bot_leads ADD COLUMN phone      VARCHAR(50);
ALTER TABLE bot_leads ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'NOVO'
                              CHECK (status IN ('NOVO','PENDENTE','PAGO','BLOQUEADO'));
ALTER TABLE bot_leads ADD COLUMN updated_at TIMESTAMPTZ;
-- `bot_leads` NÃO tem merchant_id (o vínculo é via store_bots — Spec 74);
-- o índice de status é por (status) e as queries do portal filtram via join
-- com store_bots.merchant_id.
CREATE INDEX ix_bot_leads_status ON bot_leads (status);

CREATE TABLE chat_messages (
    id           UUID PRIMARY KEY,
    lead_id      UUID NOT NULL REFERENCES bot_leads (id) ON DELETE CASCADE,
    bot_id       UUID NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    sender_type  VARCHAR(16) NOT NULL CHECK (sender_type IN ('CLIENT','ADMIN','BOT_AUTO')),
    text_content TEXT,
    media_url    VARCHAR(500),
    is_read      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_chat_messages_lead ON chat_messages (lead_id, created_at);
```

Nota: `bot_leads` não tem `merchant_id` (o vínculo é via `store_bots` —
Spec 74); queries do portal filtram por `store_bots.merchant_id`.

### D2.2 Camada JPA

- `BotLeadJpaEntity` estendida (campos CRM) + `BotLeadJpaRepository`
  (busca `q`, filtros, paginação — query nativa/JPQL com join em `store_bots`
  para o escopo do lojista).
- `ChatMessageJpaEntity` + `ChatMessageJpaRepository`
  (`findByLeadIdOrderByCreatedAt`, `countByLeadIdAndIsReadFalse`,
  `markAllRead(leadId)`) + mapper.
- Portas `CrmLeadRepository`/`ChatMessageRepository` + adapters JPA.

## D3 — Domínio

- `CrmLeadStatus { NOVO, PENDENTE, PAGO, BLOQUEADO }` (mapa do funil).
- `ChatSenderType { CLIENT, ADMIN, BOT_AUTO }`.
- `LeadStatusDerivationService` (D4) — deriva o status do lead a partir dos
  eventos do funil (Spec 75); `BLOQUEADO` é flag (não derivado do funil).

## D4 — Derivação de status

- Chamado **na mesma transação** dos eventos do funil (Spec 75):
  - `recordLead` (novo lead) → `NOVO`.
  - `recordCheckout` (checkout_started) → `PENDENTE` (se não PAGO/BLOQUEADO).
  - `recordPaid` → `PAGO` (se não BLOQUEADO).
  - Bloqueio (D6) → `BLOQUEADO` (sobrescreve).
- Nunca re-scan em batch; consistência imediata.

## D5 — Ponte de chat (envio)

`POST /api/v1/portal/crm/leads/{id}/messages` `{ text? | mediaUrl? }`:

1. Valida: lead do lojista (404), status ≠ `BLOQUEADO` (409 `lead_blocked`),
   texto ou mídia (400).
2. Envia via integração da Spec 74: Telegram `sendMessage(chatId = user_id)`
   (adapter `TelegramBotApi`); Discord DM (manager D6).
3. Sucesso → persiste `chat_messages` (sender `ADMIN`) + auditoria
   `CHAT_MESSAGE_SENT`; falha `403 blocked` → `BLOQUEADO` (D6); outra falha →
   502 com mensagem (sem persistir como enviada).
4. Rate limit das plataformas respeitado pelo adapter (Spec 74) — nunca
   síncrono em rajada (o lojista envia 1:1, volume baixo).

## D6 — Detecção de bloqueio

`LeadBlockedException` no caminho de envio: Telegram `403` com "bot was
blocked by the user" (ou Discord falha de DM por bloqueio) → transação
`markBlocked(leadId, reason)` → status `BLOQUEADO` + auditoria `LEAD_BLOCKED`
(actor: MERCHANT via painel ou SYSTEM via bot) + resposta `409
lead_blocked`. `PlatformAction` += `LEAD_BLOCKED` (seção `// Bots (Spec 74)`).

## D7 — Recebimento (webhook/manager → persistir → SSE)

- **Telegram**: webhook da Spec 74 (D5.3) — mensagem privada de
  `platform_user_id` conhecido em **handoff `HUMAN`** (Spec 90) → persiste
  `CLIENT` + `ChatEventPublisher` (D8) e **não publica** `TELEGRAM_UPDATE`; em
  `BOT` (default) → publica `TELEGRAM_UPDATE` (o funil do bot responde; sem
  persistência no histórico do CRM — decisão alinhada com a Spec 90 C3);
  desconhecido → `recordLead` (Spec 75) + publica.
- **Discord**: handler de interação (`FoxBotInteractionController` → Spec 75) —
  idem para DMs (lead em `HUMAN` → persiste `CLIENT` + SSE, `text` opcional no
  payload; `BOT` → registra o lead apenas).
- Persistência **antes** do publish (at-least-once; o SSE pode reentregar,
  o cliente dedup por `messageId`).

## D8 — Push SSE + fan-out Redis

- `ChatEventPublisher.publish(merchantId, event)`:
  1. Publica no Redis pub/sub (`nyx:crm:events:{merchantId}`) — via
     `StringRedisTemplate` (client já existe, Spec 43/66).
  2. **Fail-open in-memory** (ADR-010): se o Redis falhar, entrega aos SSEs
     locais da instância (single-instance degrada sem perder).
- `CrmSseController`: `GET /api/v1/portal/crm/stream` (SSE,
  ROLE_MERCHANT_PORTAL) — `SseEmitter` por conexão; subscriber no channel do
  lojista (via `RedisMessageListenerContainer`); heartbeat a cada 15s;
  `Last-Event-ID` para retomar do buffer curto (últimos N eventos em memória
  por instância).
- Conexão SSE é **por lojista** (um stream para todos os chats abertos —
  o cliente filtra por `leadId` no payload).

## D9 — REST (portal)

| Método | Rota | Auth | Observação |
|---|---|---|---|
| GET | `/api/v1/portal/crm/leads` | ROLE_MERCHANT_PORTAL | busca `q` + filtros `botId/status/from/to` + paginação |
| GET | `/api/v1/portal/crm/leads/{id}` | ROLE_MERCHANT_PORTAL | detalhe + status (404 cross-lojista) |
| GET | `/api/v1/portal/crm/leads/{id}/messages` | ROLE_MERCHANT_PORTAL | histórico paginado |
| POST | `/api/v1/portal/crm/leads/{id}/messages` | ROLE_MERCHANT_PORTAL | enviar (D5) |
| POST | `/api/v1/portal/crm/leads/{id}/read` | ROLE_MERCHANT_PORTAL | marca lida (CLIENT) |
| GET | `/api/v1/portal/crm/summary` | ROLE_MERCHANT_PORTAL | cards (novos/pendentes/pagos/bloqueados/total) |
| GET | `/api/v1/portal/crm/stream` | ROLE_MERCHANT_PORTAL | SSE (D8) |

## D10 — Auditoria e métricas

- `PlatformAction` += `CHAT_MESSAGE_SENT`, `LEAD_BLOCKED` (seção `// Bots
  (Spec 74)`); trilha na mesma transação (Spec 41).
- Métricas `nyx_crm_*` (Spec 53): `mensagens_enviadas`, `mensagens_recebidas`,
  `sse_connected`, `bloqueios`.

## D11 — Configuração

```yaml
foxcash:
  crm:
    sse-heartbeat-ms: 15000
    sse-buffer-size: 50        # eventos em memória p/ Last-Event-ID
    chat-event-channel-prefix: "nyx:crm:events:"
    redis-enabled: ${NYX_CRM_REDIS_ENABLED:true}   # false = só in-memory
```

`CrmProperties` + binding contra o yml real (padrão Spec 68/L16).
