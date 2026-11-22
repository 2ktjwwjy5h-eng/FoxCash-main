---
name: Handoff humano no CRM (pausar o bot e atender manualmente)
status: audited
---

# Spec 90 — Task: Handoff humano no CRM

> Implementado **junto com o CRM** (Specs 83/84 — `implemented` na mesma fase):
> o handoff usa `chat_messages`, o push SSE e o Live Chat modal daquela fase.

## F0 — Domínio + migration

- [x] **T0.1** — Migration **`V57__crm.sql`** (fundida com a Spec 83 — mesma
      tabela; o requirements dizia V56, mas a V56 é da Spec 81 — próximo livre
      é V57): `ALTER TABLE bot_leads ADD COLUMN handoff_status VARCHAR(16) NOT
      NULL DEFAULT 'BOT' CHECK (handoff_status IN ('BOT','HUMAN'));`
- [x] **T0.2** — Enum `LeadHandoffStatus { BOT, HUMAN }`; `BotLead` +=
      `handoffStatus` com `pauseBot()`/`resumeBot()` (idempotentes — repetir o
      mesmo estado é no-op) + `id` (UUID persistido — `chat_messages.lead_id`);
      `BotLeadJpaEntity` + mapper salvam/restauram a coluna.

## F1 — Interceptação no webhook (Telegram + Discord)

- [x] **T1.1** — `TelegramWebhookController.receive` (Spec 74 D5.3): após
      `recordLead`, se o lead existe com `handoffStatus = HUMAN` → persiste
      `chat_messages` (sender `CLIENT`) + push SSE (Spec 83 D7/D8) e **não
      publica `TELEGRAM_UPDATE`** (204); senão, comportamento atual (publica).
      Callbacks (`callback_query`) do lead em HUMAN também são suprimidos.
- [x] **T1.2** — `FoxBotInteractionController` (`POST
      /api/v1/foxcash/bots/{id}/interaction` → `DiscordBotManager.recordInteraction`
      — Spec 75): lead em `HUMAN` → persiste `CLIENT` + SSE; o texto da
      interação é campo opcional do payload (`text`).
- [x] **T1.3** — Testes: `TelegramWebhookControllerTest` — HUMAN → `CLIENT` +
      sem `TELEGRAM_UPDATE`; BOT → publica; `BotLeadTest` — `pauseBot`/
      `resumeBot` idempotentes.

## F2 — API do portal + auditoria

- [x] **T2.1** — `LeadHandoffService.setHandoff(merchantId, leadId, mode)`:
      lead do lojista (404 cross-lojista), toggle no domínio + save + auditoria
      `LEAD_HANDOFF` (before/after) na mesma transação; repetir estado é no-op.
- [x] **T2.2** — `GET /api/v1/portal/crm/leads/{id}` += `handoffStatus`
      (aditivo); `PUT /api/v1/portal/crm/leads/{id}/handoff`
      `{ "mode": "BOT" | "HUMAN" }` → 200 com o lead atualizado (modo inválido
      → 400 via `IllegalArgumentException`).
- [x] **T2.3** — `PlatformAction` += `LEAD_HANDOFF` (seção `// Bots (Spec 74)`).
- [x] **T2.4** — Testes: `LeadHandoffServiceTest` (toggle/404/idempotência/
      auditoria), `PortalCrmControllerTest` (GET handoffStatus, PUT 200/400/404).

## F3 — Frontend (Spec 84 — Live Chat modal)

- [x] **T3.1** — Botão "Pausar bot"/"Retomar bot" no cabeçalho do Live Chat
      conforme `handoffStatus` (PUT `/handoff`); feedback `SuccessBanner`/
      `ErrorBanner` (Spec 73).
- [x] **T3.2** — Indicador visual em `HUMAN` ("Atendimento manual — bot
      pausado") + dot na linha da tabela; mensagens recebidas continuam via SSE;
      envio do painel inalterado.
- [x] **T3.3** — `npm run build` (tsc) limpo no portal.

## F4 — E2E e fechamento

- [x] **T4.1** — E2E `crm-handoff.spec.js`: webhook mockado → mensagem de lead
      (BOT) → `TELEGRAM_UPDATE` publicado; pausar bot (HUMAN) → nova mensagem →
      persistida `CLIENT` + **sem** novo `TELEGRAM_UPDATE` no `notification_outbox`;
      retomar (BOT) → volta a publicar; auditoria `LEAD_HANDOFF` presente. **3/3**.
- [x] **T4.2** — `mvn test` **1640/0/0** verde + suíte E2E completa re-executada
      pós-fix (6 novos specs); atualizar roadmap/status.

## Verificação (`/spec-verify 90`)

- [ ] Checar critérios ↔ design ↔ task contra o código real (migration livre,
      `TelegramWebhookController`, `BotLead`/`BotLeadService`, `PlatformAction`,
      CRUD do portal, Live Chat da Spec 84) — sem achados.

## §Implementação — decisões e desvios (registro)

1. **Migration fundida `V57__crm.sql`** (Spec 83 + 90): ver decisão 1 da Spec 83.
2. **`from.id` do Telegram é NUMÉRICO** — o `extractPlatformUserId` (Spec 75)
   usa `isNumber()`; o E2E do handoff precisou enviar `from: { id: 7770001 }`
   (literal numérico) — um update com id em string não cria lead.
3. **Interceptação Discord no endpoint de interação** (não num listener interno
   do manager) — achado do verify registrado no requirements 90 C4; o payload
   ganhou `text` opcional (Spec 83 D7).
4. **`BotLead` ganhou `id` (UUID persistido)** — necessário para
   `chat_messages.lead_id` e para o response do CRM; `create()` gera id novo e
   `restoreCrm` restaura; factories do funil não mudaram de assinatura.

## §Auditoria (2026-08-14) — `/spec-audit 90`: 9/9 critérios OK

Verificações contra o código real: **C1** migration `V57__crm.sql` fundida
(`handoff_status VARCHAR(16) NOT NULL DEFAULT 'BOT' CHECK IN ('BOT','HUMAN')`);
**C2** `LeadHandoffStatus` + `BotLead.pauseBot()/resumeBot()` idempotentes (+
`id` persistido referenciado por `chat_messages.lead_id`); **C3** interceptação
Telegram no `TelegramWebhookController` (HUMAN → `persistInbound` CLIENT + SSE e
**sem** `TELEGRAM_UPDATE`; BOT → publica; callbacks suprimidos); **C4**
interceptação Discord no `FoxBotInteractionController` (`recordInteraction` +
persistência `CLIENT` com `text` opcional em HUMAN); **C5** `BOT_AUTO`
(agendadas 51/WELCOME 79/entrega 49) não afetado — o bloqueio é só do
encaminhamento ao funil; **C6** `GET /crm/leads/{id}` com `handoffStatus`
(aditivo) + `PUT /crm/leads/{id}/handoff` `{mode}` → 200, 400 modo inválido,
404 cross-lojista; **C7** auditoria `LEAD_HANDOFF` com before/after na mesma
transação (`LeadHandoffService`); **C8** UI Pausar/Retomar no cabeçalho do Live
Chat + indicador "Atendimento manual — bot pausado" + dot na tabela; **C9**
testes — `LeadHandoffServiceTest` 4/4, `BotLeadTest` (idempotência), E2E
`crm-handoff.spec.js` 3/3 (webhook BOT → publica; HUMAN → CLIENT sem
`TELEGRAM_UPDATE`; retomar → volta a publicar; auditoria presente). `mvn test`
**1644/0/0** re-executado + suíte E2E completa sem regressão.

**Achados: 0 de código.** Observação registrada: a suíte E2E completa depende da
ordem de build portal → console (achado da Spec 73 — o `emptyOutDir` do portal
apaga `static/console/`); revalidado na auditoria (portal + console rebuildados).
