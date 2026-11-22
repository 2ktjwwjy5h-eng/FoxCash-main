---
name: Handoff humano no CRM (pausar o bot e atender manualmente)
status: audited
---

# Spec 90 — Design: Handoff humano no CRM

## D1 — Conceito

O handoff é uma **camada sobre o CRM** (Specs 83/84): o estado `BOT`/`HUMAN`
por lead vive no `bot_leads` (Spec 75) e o **ponto de interceptação é o
webhook** — o mesmo lugar onde o update chega hoje (Spec 74 D5.3
`TelegramWebhookController`). Em `HUMAN`, o backend **consome** a mensagem para
o CRM (persiste + SSE) e **não publica** o `TELEGRAM_UPDATE` — o funil do bot
(Spec 52) simplesmente não recebe aquele update.

### D1.1 Decisões do usuário (2026-08-14) → regras

| Decisão | Regra |
|---|---|
| Estado por lead (não por sessão separada) | `bot_leads.handoff_status`, default `BOT` — sem tabela `chat_sessions` nova |
| Interceptação no webhook | `TelegramWebhookController` (D5.3) e listener Discord (D6) |
| `HUMAN` → não publica `TELEGRAM_UPDATE` | o bot não responde; mensagem vai pro CRM (persiste + SSE) |
| `BOT_AUTO` continua | agendadas (51)/WELCOME (79)/entrega (49) não são afetadas |
| Implementar junto com o CRM | o CRM (83/84) está `verified`, não implementado |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V56__crm_handoff.sql`

Última migration real: **V55** (Spec 80). Usar **V56** (verificar o próximo
número livre; pode ser **fundida** com a migration do CRM da Spec 83 se
implementadas juntas — o handoff é uma coluna da mesma tabela).

```sql
-- Spec 90 — handoff humano (BOT automático vs HUMAN atendimento manual).
ALTER TABLE bot_leads ADD COLUMN handoff_status VARCHAR(16) NOT NULL DEFAULT 'BOT'
    CHECK (handoff_status IN ('BOT','HUMAN'));
```

Sem índice: o acesso é por lead (`findByBotIdAndPlatformUserId` — Spec 75),
nunca por status.

### D2.2 Camada JPA

- `BotLeadJpaEntity` += `handoff_status` (coluna + getter/setter).
- Mapper salva/restaura `handoffStatus`.

## D3 — Domínio

- `LeadHandoffStatus { BOT, HUMAN }` (novo enum).
- `BotLead` (Spec 75) += `handoffStatus` com `pauseBot()`/`resumeBot()`
  (transições **idempotentes**: `pauseBot()` em `HUMAN` é no-op sem erro).

## D4 — Interceptação no webhook (Telegram)

No `TelegramWebhookController.receive` (Spec 74 D5.3), no lugar do passo 4
(atual: `recordLead` + `publish(TELEGRAM_UPDATE)`), o fluxo vira:

```
1. recordLead(botId, userId)                      // idempotente (Spec 75) — garante o lead
2. lead = crmService.findByBotAndUser(botId, userId)
3. se lead.handoffStatus == HUMAN E update é mensagem privada do usuário:
     crmService.persistInboundMessage(lead, update)   // chat_messages CLIENT + SSE (Spec 83 D7/D8)
     return 204                                       // NÃO publica TELEGRAM_UPDATE (funil abortado)
4. senão: publish(TELEGRAM_UPDATE)                    // comportamento atual (Spec 74)
```

A persistência + SSE reusam os serviços da Spec 83 (`ChatMessageService`/
`ChatEventPublisher` — D7/D8 da 83). Regra: **qualquer** update (mensagem OU
callback_query) de lead em `HUMAN` é consumido (o callback "comprar" também é
suprimido — o bot não reage ao funil). `recordLead` continua rodando em ambos
os casos (o lead precisa existir).

**Nota de ambiente:** em produção o Telegram usa `setWebhook` → o update passa
pelo backend (único ponto de entrada — o handoff funciona). Em dev o bot
grammY usa long polling (`bot.start()`), que **não passa pelo backend** — o
handoff não se aplica a esse caminho; os E2E cobrem o caminho do webhook
(POST no `/webhooks/telegram/{id}/{secret}`), que é o de produção.

## D5 — Interceptação (Discord)

**Achado do verify:** o ponto real de entrada de interações Discord é o
endpoint `POST /api/v1/foxcash/bots/{id}/interaction` →
`DiscordBotManager.recordInteraction(botId, platformUserId)` (Spec 75),
chamado pelo renderizador (bots/discord). A interceptação de handoff fica
**nesse handler**: lead em `HUMAN` → persiste `chat_messages` (CLIENT) + SSE e
**não encaminha a interação ao funil do bot Discord** (Spec 52).

Nota de dependência (Spec 83/D7): para persistir a mensagem como `CLIENT` com
conteúdo, o endpoint de interação precisa receber o texto da DM — o shape do
payload de interação com `text` é decidido na implementação, junto com o CRM.
Implementação espelha o D4 (mesmo serviço `ChatMessageService`).

## D6 — API do portal

| Método | Rota | Auth | Observação |
|---|---|---|---|
| GET | `/api/v1/portal/crm/leads/{id}` | ROLE_MERCHANT_PORTAL | += `handoffStatus` (aditivo — Spec 83) |
| PUT | `/api/v1/portal/crm/leads/{id}/handoff` | ROLE_MERCHANT_PORTAL | `{ "mode": "BOT" \| "HUMAN" }` → 200 com o lead |

`LeadHandoffService.setHandoff(merchantId, leadId, mode)`:

1. Lead do lojista (404 cross-lojista — padrão Spec 83); `mode` inválido → 400.
2. `pauseBot()`/`resumeBot()` no domínio + save.
3. Auditoria `LEAD_HANDOFF` (D7) na mesma transação.

**Handoff em lead `BLOQUEADO`:** permitido (sem efeito prático no envio — o
send já recusa 409 `lead_blocked`; o estado fica consistente).

## D7 — Auditoria

`PlatformAction` += `LEAD_HANDOFF` (na seção `// Bots (Spec 74)` — junto com
as ações do CRM: `CHAT_MESSAGE_SENT`/`LEAD_BLOCKED`, Spec 83 D10). Trilha (Spec
41) na mesma transação do `setHandoff`: actor = MERCHANT, payload
`{ leadId, before: BOT|HUMAN, after: BOT|HUMAN }`.

## D8 — Frontend (Spec 84 — Live Chat modal)

No cabeçalho do Live Chat (Spec 84):

- **`handoffStatus = BOT`** → botão "Pausar bot" (ícone de pausa); ao clicar →
  `PUT handoff {mode: HUMAN}` → botão vira "Retomar bot" + banner discreto
  "Atendimento manual — bot pausado para este cliente".
- **`handoffStatus = HUMAN`** → botão "Retomar bot" (ícone de play) → `PUT
  handoff {mode: BOT}`.
- Confirmação leve (não é ação destrutiva — sem `ConfirmDialog`; feedback via
  `SuccessBanner`/`ErrorBanner` — Spec 73).
- O histórico/detalhe do lead (Spec 84) exibe o `handoffStatus`.
- Mensagens recebidas continuam chegando via SSE (Spec 83 D8) independente do
  estado; o envio do painel (Spec 83 D5) funciona sempre.

## D9 — Testes

**Unitários/integração (Java):**
- `BotLeadTest` — `pauseBot`/`resumeBot` idempotentes.
- `TelegramWebhookControllerTest` (novo caso) — lead `HUMAN`: mensagem
  persistida como `CLIENT` + `ChatEventPublisher` chamado + **`publish(TELEGRAM_
  UPDATE)` NUNCA**; lead `BOT`: publica (comportamento atual); lead inexistente:
  `recordLead` cria e publica.
- `LeadHandoffServiceTest` — toggle BOT↔HUMAN, 404 cross-lojista, 400 modo
  inválido, auditoria `LEAD_HANDOFF` com before/after na mesma transação.
- `PortalCrmControllerTest` — `GET` com `handoffStatus`; `PUT handoff` 200/400/
  404; RBAC (ROLE_MERCHANT_PORTAL).

**E2E Playwright (`crm-handoff.spec.js`):** webhook mockado → mensagem de lead
conhecido (BOT) → update publicado; pausar bot (HUMAN) → nova mensagem →
persistida `CLIENT` no `chat_messages` + `chat_messages` recebe SSE
(comportamento por `is_read`/leitura) + **`notification_outbox` SEM novo
`TELEGRAM_UPDATE`** (o bot não responde); retomar (BOT) → mensagem volta a
publicar; auditoria `LEAD_HANDOFF` no `platform_activity_log`.

**CI:** job e2e herda envs existentes.
