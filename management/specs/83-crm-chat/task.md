---
name: CRM — base de clientes e chat em tempo real (backend)
status: audited
---

# Spec 83 — Task: CRM (base de clientes e Live Chat)

## F0 — Domínio + migration (critérios 1–2)

- [x] T0.1: Migration **`V57__crm.sql`** (D2.1 — o próximo número livre; o
      requirements dizia V53, escrito antes da Specs 81/89/90) — **fundida com o
      handoff da Spec 90** (mesma tabela, decisão do design 90/D2.1). `ALTER
      bot_leads` (name/username/email/phone/status/updated_at +
      handoff_status) + `chat_messages` + índices.
- [x] T0.2: `BotLeadJpaEntity` estendida + `BotLeadJpaRepository`
      (**`JpaSpecificationExecutor`** — filtros condicionais, padrão Spec 89 §1)
      + `ChatMessageJpaEntity`/`ChatMessageJpaRepository` (histórico, count
      não lida, markAllRead) + mappers.
- [x] T0.3: Portas (`BotLeadRepository` estendida + `ChatMessageRepository`) +
      adapters.
- [x] T0.4: Enum `CrmLeadStatus` + `ChatSenderType` + `LeadStatusDerivationService`
      (D3/D4) — derivação na mesma transação dos eventos do funil (Spec 75:
      `BotFunnelService.recordCheckout/recordPaid`).
- [x] T0.5: `PlatformAction` += `CHAT_MESSAGE_SENT`, `LEAD_BLOCKED` (seção
      `// Bots (Spec 74)`).
- [x] T0.6: Testes — `BotLeadTest` (handoff idempotente), derivação coberta pelo
      funil existente, migration aplica limpa.

## F1 — REST portal (critérios 3–5, 11)

- [x] T1.1: `CrmLeadService` — lista (busca/filtros/paginação), detalhe,
      histórico, summary (cards), envio, marcar lida, não lidas.
- [x] T1.2: `PortalCrmController` — `GET leads`, `GET leads/{id}`, `GET
      leads/{id}/messages`, `POST leads/{id}/messages`, `POST leads/{id}/read`,
      `GET summary`, `GET stream` (SSE); cross-lojista → 404.
- [x] T1.3: Envio (D5) — `CrmLeadService.sendMessage`: valida (lead do lojista,
      status ≠ BLOQUEADO 409, texto ou mídia 400), envia via Telegram
      `sendMessage` (Spec 74 — `TelegramBotApi` ganhou o método + detecção de
      403 "bot was blocked"), persiste `ADMIN` após sucesso, auditoria.
- [x] T1.4: Testes REST — `PortalCrmControllerTest` (GET com handoffStatus, PUT
      handoff 200/400/404, envio ADMIN, SSE emitter).

## F2 — Recebimento + bloqueio (critérios 6–7, 10)

- [x] T2.1: Webhook Telegram (Spec 74 D5.3) — mensagem privada de lead
      conhecido → persiste `CLIENT` + publish; desconhecido → `recordLead`
      (Spec 75) + persiste. (No handoff da Spec 90, o HUMAN consome sem publicar.)
- [x] T2.2: Interação Discord (Spec 74 D6 — `FoxBotInteractionController` +
      `DiscordBotManager.recordInteraction`) — idem para DMs (lead HUMAN →
      CLIENT + SSE; o text da interação é opcional no payload).
- [x] T2.3: `LeadBlockedException` + `markBlocked` (D6) — Telegram 403 "bot
      was blocked" → status `BLOQUEADO` + auditoria `LEAD_BLOCKED` + 409
      `lead_blocked` no envio (handler no `GlobalExceptionHandler`).
- [x] T2.4: Testes — `TelegramWebhookControllerTest` (novos casos de handoff
      HUMAN/BOT), bloqueio coberto pelo fluxo de envio.

## F3 — SSE + fan-out (critérios 8–9)

- [x] T3.1: `ChatEventPublisher` (D8) — entrega local in-memory + publica no
      Redis pub/sub (`nyx:crm:events:{merchantId}`) via `StringRedisTemplate`;
      **fail-open (ADR-010)**; `foxcash.crm.redis-enabled` (default false em
      dev — sem Redis no CI; produção liga via env).
- [x] T3.2: `CrmRedisConfig` — `RedisMessageListenerContainer` condicional
      (`redis-enabled=true`) assina `nyx:crm:events:*` e encaminha aos SSEs
      locais. SSE no `PortalCrmController.stream` (`SseEmitter`, buffer curto
      para `Last-Event-ID`, heartbeat via `CrmProperties`).
- [x] T3.3: Testes — `PortalCrmControllerTest.stream` (emitter do publisher).

## F4 — Fechamento (critério 12)

- [x] T4.1: `CrmProperties` com binding contra o yml real (padrão Spec 68/L16)
      + bloco `foxcash.crm.*` no `application.yml`.
- [x] T4.2: E2E — `crm.spec.js` (Spec 84: cards, busca, chat com histórico,
      envio ADMIN, lead bloqueado desabilita envio) + `crm-handoff.spec.js`
      (Spec 90: webhook BOT → publica; HUMAN → CLIENT sem TELEGRAM_UPDATE;
      retomar → volta a publicar; auditoria LEAD_HANDOFF).
- [x] T4.3: `mvn test` **1640/0/0** verde sem regressão + E2E completo
      re-executado pós-fix (6 novos specs).
- [ ] T4.4: `/spec-verify 83` e depois `/spec-audit 83` (critérios 1–12).

## §Implementação — decisões e desvios (registro)

1. **Migration `V57__crm.sql` (fundida com a Spec 90)**: o requirements dizia
   `V53__crm.sql` (escrito antes da Specs 81/89/90 — V52-56 já usadas); o
   próximo número livre é **V57**. Como o handoff (90) é coluna da MESMA tabela
   (`bot_leads.handoff_status`), as duas migrations foram **fundidas em V57**
   (enriquecimento do CRM + `chat_messages` + `handoff_status`) — decisão
   prevista no design 90/D2.1.
2. **Filtros dinâmicos via Specification (não JPQL com `IS NULL OR`)**: o mesmo
   achado da Spec 89 §1 — `? IS NULL OR` com parâmetros (Instant/UUID) quebra
   no PostgreSQL real por inferência de tipo. `BotLeadJpaRepository` ganhou
   `JpaSpecificationExecutor` e a busca/summary usam predicates condicionais
   (padrão `MasterAuditPersistenceAdapter`). O summary agrupa por status em
   memória (Specification sem filtro de status; cards do painel — volume baixo).
3. **Envio do painel cobre Telegram** (Bot API `sendMessage`, chat_id =
   `platform_user_id`): o Discord não tem DM REST no backend (o gateway vive no
   renderizador, Spec 52 — decisão stateless) — envio por bot Discord retorna
   502 `message_send_failed` com mensagem clara (evolução fora do escopo).
4. **SSE no frontend via `fetch` + `ReadableStream`** (parse manual do SSE): o
   `EventSource` nativo do browser NÃO envia o header `Authorization` — o hook
   `useCrmStream` usa `fetch` com Bearer e reconexão com backoff.

## §Auditoria (2026-08-14) — `/spec-audit 83`: 12/12 critérios OK

Verificações contra o código real: **C1** migration `V57__crm.sql` (enriquecimento
do `bot_leads` + `chat_messages` + índices, fundida com a 90); **C2** derivação de
status NA MESMA transação do funil (`BotFunnelService` → `LeadStatusDerivationService`);
**C3** `GET /crm/leads` com `q`/`botId`/`status`/`from`/`to` + paginação e 404
cross-lojista; **C4** `GET /crm/summary` (novos/pendentes/pagos/bloqueados/total);
**C5** envio com validações (400 texto-ou-mídia, 409 `lead_blocked`, 502
`message_send_failed`) + persistência `ADMIN` após sucesso; **C6/C7** recebimento
Telegram (`TelegramWebhookController`) e Discord (`FoxBotInteractionController`) —
persistência `CLIENT` + SSE em handoff `HUMAN` (Spec 90), `TELEGRAM_UPDATE` em
`BOT`; **C8** SSE com heartbeat + `Last-Event-ID` (buffer curto); **C9** fan-out
Redis pub/sub com fail-open (`ChatEventPublisher` + `CrmRedisConfig` condicional);
**C10** detecção de bloqueio (Telegram 403 → `LeadBlockedException` →
`markBlocked` + auditoria + 409); **C11** `POST /read` + `countByLeadIdAndReadFalse`;
**C12** auditoria (`CHAT_MESSAGE_SENT`/`LEAD_BLOCKED`) + **métricas `nyx_crm_*`
implementadas nesta auditoria** (ver achado 1). `mvn test` **1644/0/0** re-executado
(1640 + 4 do `CrmLeadServiceTest` novo).

**Achados corrigidos (4) — 0 abertos:**

1. **MÉDIO — métricas `nyx_crm_*` ausentes (C12)**: o critério exigia
   mensagens enviadas/recebidas, SSE conectados e bloqueios (padrão Spec 53) e
   a implementação não os registrava. Adicionados: `nyx_crm_messages_sent_total`
   (envio `ADMIN`), `nyx_crm_messages_received_total` (mensagem `CLIENT`
   persistida), `nyx_crm_lead_blocked_total` (bloqueio) no `CrmLeadService` e
   `nyx_crm_sse_connected_total` (conexão) no `ChatEventPublisher` — via
   `MeterRegistry` injetado (padrão `nyx_tracking_*_total` da Spec 81). Teste
   unitário novo `CrmLeadServiceTest` (4/4: envio, bloqueado, 409 por status,
   recebimento).
2. **BUG (cross-spec — ver Spec 84 §Auditoria)**: rota `/crm` fora do
   `SecurityConfig.spaPublicPaths()` + `PortalSpaController` (F5 de `/crm`
   devolvia 401/404) — mesma classe do achado de `/broadcasts` da Spec 80;
   corrigido nos dois pontos.
3. **DOC — C6/D7 vs código**: os documentos diziam "persiste `CLIENT` + SSE"
   para todo lead conhecido; o código persiste apenas em handoff `HUMAN`
   (em `BOT` publica `TELEGRAM_UPDATE` — decisão da Spec 90 C3). requirements
   C6 e design D7 alinhados ao comportamento real (o histórico do CRM reflete
   as mensagens do cliente durante atendimento manual; em modo `BOT` o bot
   responde pelo funil).
4. **MÉDIO — contagem/marcação de não lidas sem filtro de remetente (C11)**:
   `countByLeadIdAndReadFalse` contava TODAS as mensagens não lidas (inclusive
   `ADMIN` enviadas pelo próprio lojista) e `markAllRead` marcava todas —
   contradizendo C11/C7 (o indicador é de mensagens `CLIENT`). Exposto pelo E2E
   `crm.spec.js` teste 2 (enviar ADMIN → não lida voltava a 1). Corrigido no
   `ChatMessageJpaRepository`: `countUnreadClient` (CLIENT-only) e `markAllRead`
   com `AND senderType = 'CLIENT'`; adapter atualizado. E2E re-executado verde.
