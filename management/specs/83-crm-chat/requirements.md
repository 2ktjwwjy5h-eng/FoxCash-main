---
name: CRM — base de clientes e chat em tempo real (backend)
status: audited
references:
  - "management/vision.md#V60"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 83 — CRM: base de clientes e chat em tempo real (backend)

## O que entrega

A **central de clientes (CRM)** e a **ponte de Live Chat bidirecional** entre
o painel e os clientes no Telegram/Discord — sobre os leads da Spec 75 e a
integração da Spec 74. O sistema externo analisado (PRD do irmão) usa
**Socket.io**; **aqui**: **SSE (`SseEmitter`, built-in do spring-web — zero
dependência nova) + Redis pub/sub** para fan-out entre instâncias (padrão
`RedisAlertCounterStore` com fail-open in-memory, ADR-010) — **sem sticky
sessions**, consistente com a HA stateless (Spec 62). Enviar do painel =
**REST** (auditável); receber = webhook/manager → persiste → SSE.

1. **`bot_leads` enriquecido** — nome, username, e-mail, phone e `status`
   (Spec 75 tem `bot_id`, `platform_user_id`, `first_seen_at`).
2. **Status derivado do funil** (Spec 75): `NOVO` (só lead), `PENDENTE`
   (checkout sem paid), `PAGO` (paid), `BLOQUEADO` (403 do Telegram/ban).
3. **`chat_messages`** — histórico persistido: `lead_id`, `bot_id`, sender
   `CLIENT/ADMIN/BOT_AUTO`, texto/mídia, `is_read`.
4. **Ponte de chat** — enviar via REST (reusa `TelegramBotApi`/gerenciador
   Discord da Spec 74: `sendMessage`/DM) e receber via webhook Telegram
   (Spec 74 D5.3)/listener Discord → persiste + **push SSE** para a janela
   aberta.
5. **Detecção de bloqueio** — Telegram `403 bot was blocked by the user` no
   envio → lead `BLOQUEADO` automático + auditoria.
6. **Endpoints** — lista de clientes (busca nome/@/e-mail/ID; filtros bot/
   status/período; paginação) e histórico do chat (paginado).

## Contexto (por que agora)

A Spec 75 criou o lead/funil e a 74 a integração por plataforma; sem o CRM, o
lojista não vê a base de clientes nem consegue **atender em tempo real** —
o valor central do módulo do irmão. Decisão do usuário (2026-08-12):
implementar a mesma ideia **melhor** — SSE + Redis pub/sub (sem Socket.io),
status **derivado automaticamente** (não manual), detecção de bloqueio no
caminho de envio.

## Critérios de aceite

1. **Migration `V53__crm.sql`** (verificar próximo número livre): `ALTER
   bot_leads ADD COLUMN name/username/email/phone/status` (status CHECK IN
   ('NOVO','PENDENTE','PAGO','BLOQUEADO'), default 'NOVO', `updated_at`);
   tabela `chat_messages` (`id UUID PK`, `lead_id UUID NOT NULL REFERENCES
   bot_leads ON DELETE CASCADE`, `bot_id UUID NOT NULL REFERENCES store_bots
   ON DELETE CASCADE`, `sender_type VARCHAR CHECK IN
   ('CLIENT','ADMIN','BOT_AUTO')`, `text_content TEXT`, `media_url
   VARCHAR(500)`, `is_read BOOLEAN DEFAULT FALSE`, `created_at`); índice
   `(lead_id, created_at)`.
2. **Derivação de status** (serviço `LeadStatusDerivationService`): `NOVO` =
   lead sem evento `checkout_started`; `PENDENTE` = `checkout_started` sem
   `paid`; `PAGO` = com `paid` (Spec 75); `BLOQUEADO` = flag de bloqueio
   (D6). Atualizado **na mesma transação** dos eventos do funil (Spec 75) —
   nunca re-scan em batch (consistência imediata).
3. **CRUD/leitura no portal** (ROLE_MERCHANT_PORTAL, escopo por lojista):
   `GET /api/v1/portal/crm/leads` (busca `q` por name/username/email/
   platform_user_id; filtros `botId`, `status`, `from`/`to`; paginação) e
   `GET /api/v1/portal/crm/leads/{id}` (detalhe + status). Cross-lojista →
   404.
4. **Resumo (cards)**: `GET /api/v1/portal/crm/summary` → `{ novos, pendentes,
   pagos, bloqueados, total }` (período opcional) — alimenta os cards do
   painel (Spec 84).
5. **Enviar mensagem**: `POST /api/v1/portal/crm/leads/{id}/messages`
   `{ text? | mediaUrl? }` — reusa a integração da Spec 74 (Telegram
   `sendMessage`; Discord DM via manager); persiste `chat_messages`
   (sender `ADMIN`) **após sucesso** no provedor; valida: mensagem ou mídia
   pelo menos um; lead `BLOQUEADO` → 409 `lead_blocked` (envio recusado).
6. **Receber mensagem (Telegram)**: o webhook da Spec 74 (D5.3), ao receber
   mensagem privada de um lead conhecido (`platform_user_id`) em **handoff
   `HUMAN`** (Spec 90 — atendimento manual), persiste `chat_messages` (sender
   `CLIENT`) e publica no Redis → SSE (D8). Em handoff `BOT` (default), o
   update é publicado como `TELEGRAM_UPDATE` no outbox (Spec 74 — o bot
   responde; a mensagem não entra no histórico do CRM — decisão alinhada com a
   Spec 90 C3). Mensagem de desconhecido → cria lead (Spec 75) e publica.
7. **Receber mensagem (Discord)**: listener do gerenciador de conexão
   (Spec 74 D6) faz o mesmo para DMs recebidas.
8. **Push SSE**: `GET /api/v1/portal/crm/stream` (SSE, autenticado
   ROLE_MERCHANT_PORTAL) — eventos `{ type: "message", leadId, message }`
   para o lojista; heartbeat; reconexão com `Last-Event-ID` (retoma
   mensagens não entregues do buffer curto, padrão SSE).
9. **Fan-out multi-instância**: `ChatEventPublisher` — publica no Redis
   pub/sub (channel `nyx:crm:events:{merchantId}`); cada instância assina e
   encaminha aos SSEs locais; **fail-open in-memory** (single-instance,
   ADR-010); sem sticky sessions.
10. **Detecção de bloqueio**: no envio (critério 5), se o Telegram responde
    `403` com "bot was blocked by the user" → `LeadBlockedException` →
    lead `BLOQUEADO` + auditoria `LEAD_BLOCKED` + resposta 409
    `lead_blocked`; Discord: falha de DM com erro de bloqueio → idem.
11. **Marcar lida**: `POST /api/v1/portal/crm/leads/{id}/read` — `is_read =
    true` nas mensagens `CLIENT`; o indicador de não lida do painel (Spec 84)
    usa `COUNT(chat_messages WHERE is_read=false)`.
12. **Auditoria e métricas**: escrita de mensagem ADMIN →
    `CHAT_MESSAGE_SENT` (Spec 41); `LEAD_BLOCKED`; métricas `nyx_crm_*`
    (mensagens enviadas/recebidas, SSE conectados, bloqueios) — Spec 53;
    `mvn test` verde + E2E (funil → status, envio/recebimento com
    Telegram/Discord mockados, SSE).

## Dependências

- **Spec 75** (funil) — derivação de status e criação de lead.
- **Spec 74** (integração) — `TelegramBotApi`, webhook D5.3, gerenciador
  Discord D6, `store_bots`.
- **Spec 52** (bots) — `sendMessage`/DM (o envio do painel reusa a mesma
  camada).
- **Specs 41/53** (auditoria/métricas) e **Spec 43/66** (Redis).
- **Spec 46/73** (portal/UX) — consumidores (Spec 84).

## Fora de escopo

- **Painel/UI** → Spec 84.
- **Etiquetas/notas manuais, pipelines de funil customizados** — status é
  automático (derivado do funil); edição manual fica como evolução.
- **Envio de mídia pelo painel** (upload na conversa) — texto primeiro;
  mídia é recebida do cliente e exibida (media_url).
- **WebSocket full-duplex** — SSE cobre o push (decisão da Spec).
