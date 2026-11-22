---
name: Broadcast — motor de disparo em massa (backend)
status: audited
references:
  - "management/vision.md#V56"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/51-mensagens-automaticas/requirements.md"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 79 — Broadcast: motor de disparo em massa (backend)

## O que entrega

O **motor de transmissão/disparo em massa** para os bots Telegram/Discord do
lojista (registrados na Spec 74). O sistema externo analisado (PRD do irmão)
usa fila Redis+BullMQ; **aqui a fila é o próprio padrão FoxCash**: o backend
orquestra (persiste o broadcast, resolve o destino, gerencia os modos) e o
bot (Spec 52) **busca pendências via API e reporta o log por destinatário** —
com rate limit nativo das libs (grammY/discord.js), sem fila nova.

1. **Tabelas `broadcasts` e `broadcast_logs`** — o broadcast (modo, destino,
   conteúdo, mídia, botões, status) e o **histórico por destinatário**
   (`SUCCESS/BLOCKED/FAILED` + `error_message`), padrão do PRD externo.
2. **Destino canal/grupo** — resolução dinâmica dos chats onde o bot é admin
   (via registro da Spec 74): lista de canais/grupos carregada por API.
3. **Modos de operação**: `NOW` (enviar agora), `SCHEDULED` (reusa o padrão de
   agendamento da Spec 51), `WELCOME` (disparo automático no evento de entrada
   — `chat_member` no Telegram, `guildMemberAdd` no Discord) e `REPOST`
   (reenvio em intervalo configurável, ex. 12h/24h).
4. **Mídia CDN**: upload de imagem (≤10MB) e vídeo/áudio (≤50MB)
   reaproveitando `file_id` do Telegram / URL da CDN — o disparo não
   re-uploada o binário por destinatário.
5. **Botões inline** (`buttons_json`): grade de botões `link` (URL) e
   `callback` (ação interna) no corpo da mensagem.
6. **Rich-text + tags dinâmicas**: formatação no formato nativo da plataforma
   (negrito/itálico/sublinhado/riscado/código/citação/spoiler) e placeholders
   `{primeiro_nome}`, `{username}`, `{data}` (o bot já tem `renderTemplate`).
7. **Nunca síncrono na requisição HTTP** — criar/agendar retorna imediatamente;
   o disparo acontece em segundo plano (bot polling).

O **enviar por bot individual** (DM) e o **agendamento de mensagens da loja**
(Spec 51) não mudam de contrato — o broadcast é uma **nova capacidade** sobre
o mesmo mecanismo.

## Contexto (por que agora)

A Spec 51 já entrega o modo Agendada (público BUYERS, texto). O módulo do
irmão mostra o valor de **transmitir para canais/grupos** com mídia, botões e
4 modos — mas com uma fila externa (BullMQ) que **não combina com nossa
stack** (Java/Spring + Redis p/ rate limit, Spec 43; jobs ShedLock, Spec 53).
Decisão do usuário (2026-08-12): implementar a mesma ideia **melhor** — sem
BullMQ, envio no bot com rate limit nativo, backend orquestrador, log por
destinatário e auditoria (Spec 41).

## Critérios de aceite

1. **Migration `V51__broadcast.sql`** (verificar próximo número livre):
   `broadcasts` (`id UUID PK`, `store_bot_id UUID NOT NULL REFERENCES
   store_bots ON DELETE CASCADE`, `mode VARCHAR CHECK IN
   ('NOW','SCHEDULED','WELCOME','REPOST')`, `target_chat_id VARCHAR`,
   `content TEXT`, `media_url VARCHAR`, `media_type VARCHAR CHECK IN
   ('image','video','audio')`, `media_file_id VARCHAR`, `buttons_json JSONB`,
   `status VARCHAR CHECK IN ('PENDING','PROCESSING','COMPLETED','FAILED')`,
   `scheduled_at TIMESTAMPTZ`, `interval_seconds INT NULL` (REPOST),
   `created_at`/`updated_at`) e `broadcast_logs` (`id`, `broadcast_id FK
   ON DELETE CASCADE`, `recipient_id`, `status CHECK IN
   ('SUCCESS','BLOCKED','FAILED')`, `error_message`, `sent_at`).
2. **CRUD no portal** (ROLE_MERCHANT_PORTAL, escopo por lojista): `POST
   /api/v1/portal/broadcasts` (cria e valida), `GET /api/v1/portal/broadcasts`
   (lista com status), `GET /{id}` (detalhe + logs), `DELETE /{id}` (cancela
   `PENDING`/agendada). Resposta imediata — disparo nunca síncrono.
3. **Destino dinâmico**: `GET /api/v1/portal/bots/{id}/chats` devolve os
   canais/grupos onde o bot tem permissão (Telegram: `getChat`/lista de
   chats do bot; Discord: guilds/canais do bot) — via integração da Spec 74;
   `target_chat_id` obrigatório para `NOW/SCHEDULED/REPOST` (400 se ausente).
4. **Modo NOW**: broadcast `PENDING` → o bot busca via API (padrão Spec 51)
   e envia; ao concluir, status `COMPLETED` (ou `FAILED` com
   `error_message`).
5. **Modo SCHEDULED**: `scheduled_at` futuro (validação: > now);
   `GET /api/v1/foxcash/broadcasts/due?dueBefore=` devolve os vencidos
   (reusando o mecanismo de `scheduled-messages` da Spec 51); confirmação via
   `POST /api/v1/foxcash/broadcasts/{id}/sent`.
6. **Modo WELCOME**: mensagem automática disparada no evento de entrada de
   membro — o bot informa o evento ao backend (`POST
   /api/v1/foxcash/broadcasts/welcome-trigger {botId, chatId, userId}`) e o
   backend devolve o conteúdo da mensagem de boas-vindas ativa para aquele
   chat (ou no-op se não houver); idempotente por `(chatId, userId)`.
7. **Modo REPOST**: `interval_seconds` (mín. 1h, ex.: 12h/24h) — o job
   `foxcash-broadcast-repost` (ShedLock, Spec 53) re-emite o broadcast a cada
   intervalo enquanto ativo (status do registro permanece `ACTIVE`/reagenda);
   cancelar via DELETE.
8. **Mídia CDN**: `POST /api/v1/portal/broadcasts/media` (upload multipart) →
   valida tipo/tamanho (imagem ≤10MB, vídeo/áudio ≤50MB) e devolve
   `media_url` + `media_file_id` (Telegram: reuso de `file_id` via CDN
   channel; Discord: URL/attachment); o disparo usa o `file_id`/URL sem
   re-upload.
9. **Botões inline**: `buttons_json` validado (estrutura `{rows: [{label,
   url?|callback?}]}`, limites de linhas/botões da plataforma: Telegram 1–8
   por linha/máx 100; Discord 1–5 por linha/máx 5); botão `link` exige `url`
   válida (SSRF-safe — reuso da validação de URL da Spec 35); `callback`
   exige `callback` válido.
10. **Rich-text + tags**: `content` validado por plataforma (Telegram
    HTML/Markdown — sem HTML arbitrário/injeção; Discord Markdown); tags
    `{primeiro_nome}/{username}/{data}` resolvidas no envio pelo
    `renderTemplate` existente (Spec 52) — preview não exige backend.
11. **Log por destinatário**: `POST /api/v1/foxcash/broadcasts/{id}/logs` recebe
    o resultado por destinatário (`SUCCESS/BLOCKED/FAILED`) — idempotente por
    `(broadcast_id, recipient_id)`; a API nunca trava a fila (falha de um
    destinatário não derruba os demais).
12. **Auditoria e jobs**: ações de escrita (criar/agendar/cancelar) com
    `PlatformAction` novo (`BROADCAST_CREATED/SCHEDULED/CANCELLED`) — Spec
    41 na mesma transação; job `foxcash-broadcast-repost` (ShedLock +
    `JobExecutionService`, Spec 53 — métricas `nyx.job.*` + trilha
    `job_runs` + alerta `JOB_FAILED`); NOW/SCHEDULED viram PROCESSING via
    **pull do bot** no `due` (sem job de due — padrão Spec 51, ver design
    D10); `mvn test` verde + E2E (NOW/SCHEDULED/WELCOME/REPOST com
    Telegram/Discord mockados).

## Dependências

- **Spec 74** (registro de bots) — `store_bots`, integração Telegram/Discord
  (chats, envio), gerenciador de conexão.
- **Specs 51/52** (agendamento/envio) — mecanismo de "vencidas", `renderTemplate`,
  envio no bot.
- **Spec 35** (outbox) — eventos e validação de URL (SSRF).
- **Specs 41/53** (auditoria/jobs) — trilha e jobs com métricas.
- **Spec 46** (portal) — consumidor da UI (Spec 80).

## Fora de escopo

- **Editor/UI** do broadcast → Spec 80.
- **Segmentação avançada** (por tags/plano/atributo) — destinatários são o
  canal/grupo (NOW/SCHEDULED/REPOST) ou o evento de entrada (WELCOME).
- **Multi-idioma** — uma mensagem por envio.
- **Mudar o mecanismo de envio da Spec 51/52** — reuso, sem reimplementar.
