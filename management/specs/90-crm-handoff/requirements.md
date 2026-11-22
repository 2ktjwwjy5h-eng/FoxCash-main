---
name: Handoff humano no CRM (pausar o bot e atender manualmente)
status: audited
references:
  - "management/vision.md#V67"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/84-crm-painel/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 90 — Handoff humano no CRM (pausar o bot e atender manualmente)

## O que entrega

O **transbordo humano** no Live Chat do CRM (Specs 83/84): pausar as respostas
automáticas do bot para **um cliente específico** e assumir a conversa
manualmente pelo painel — essencial para recuperar vendas e resolver dúvidas de
suporte. Capacidade do sistema do irmão do usuário (Spec 83 do PRD externo —
`chat_sessions` com status `BOT`/`HUMAN`) que não existia no FoxCash: hoje o
bot (Spec 52) continua respondendo ao funil/comandos mesmo quando o lojista
está atendendo pelo CRM.

1. **Estado por lead** — `bot_leads.handoff_status` (`BOT`/`HUMAN`, default
   `BOT`): o lojista alterna **por cliente**, não para o bot inteiro.
2. **Interceptação no webhook** — quando o webhook do Telegram (Spec 74 D5.3)
   ou o listener do Discord recebe mensagem de um lead em `HUMAN`, o backend
   **persiste a mensagem em `chat_messages` (sender `CLIENT`) + push SSE** e
   **aborta o encaminhamento do update para o funil do bot** (o bot não
   responde comandos/automação daquele cliente).
3. **Controles no painel** — endpoints do portal (consultar/alternar o estado)
   e botão **"Pausar bot"/"Retomar bot"** no cabeçalho do Live Chat (Spec 84),
   com trilha `LEAD_HANDOFF` na auditoria (Spec 41).

## Contexto (por que agora)

Comparação (2026-08-14) com o sistema do irmão (Spec 83 do PRD externo —
transbordo humano / chat inbox). O FoxCash já tem o CRM + Live Chat SSE
(Specs 83/84 `verified` — persistência, ponte de chat, SSE + Redis pub/sub),
mas **não tem o handoff**: o funil do bot segue ativo por lead. Decisão do
usuário (2026-08-14): implementar o handoff como camada sobre o CRM — o
**webhook** (onde o update chega, Spec 74 D5.3) é o ponto único de
interceptação no backend (ao contrário do design do irmão, que usava uma
tabela `chat_sessions` separada + polling — aqui reusamos o `bot_leads` e o
push SSE existentes).

## Critérios de aceite

1. **Migration** (próximo número livre — **V56**, verificar; pode ser fundida
   com a migration do CRM da Spec 83 se implementadas juntas):
   `ALTER TABLE bot_leads ADD COLUMN handoff_status VARCHAR(16) NOT NULL
   DEFAULT 'BOT' CHECK (handoff_status IN ('BOT','HUMAN'));` — índice não
   necessário (consulta por lead, não por status).
2. **Domínio** — enum `LeadHandoffStatus { BOT, HUMAN }`; o lead (Spec 75)
   ganha o campo `handoffStatus` com transições `pauseBot()`/`resumeBot()`
   (idempotentes — repetir o mesmo estado não é erro).
3. **Interceptação Telegram (Spec 74 D5.3)** — no `TelegramWebhookController`,
   após `recordLead` (Spec 75): se o lead existe e `handoff_status = HUMAN`,
   a mensagem privada é **persistida em `chat_messages` (sender `CLIENT`) +
   push SSE** (mesmo caminho da Spec 83 D7/D8) e o update **NÃO é publicado
   no outbox** (`TELEGRAM_UPDATE` suprimido → o funil do bot não processa);
   resposta 204. Se `BOT`, comportamento atual (publica `TELEGRAM_UPDATE`).
4. **Interceptação Discord** — no handler de interação `POST
   /api/v1/foxcash/bots/{id}/interaction` → `DiscordBotManager.recordInteraction`
   (Spec 75, chamado pelo renderizador): lead em `HUMAN` → persiste `CLIENT`
   + SSE e **não encaminha a interação ao funil do bot Discord** (Spec 52).
   (Achado do verify: o ponto real é o endpoint de interação — Spec 75 —, não
   um listener interno do manager.)
5. **Escopo do bloqueio** — o handoff afeta apenas **updates do usuário**
   (mensagens/callbacks dele). Mensagens `BOT_AUTO` (agendadas da Spec 51,
   broadcast WELCOME da Spec 79, entrega da Spec 49) **continuam** — o bot
   não "morre", só deixa de reagir ao cliente em atendimento humano.
6. **API do portal** — `GET /api/v1/portal/crm/leads/{id}` passa a incluir
   `handoffStatus` (aditivo); `PUT /api/v1/portal/crm/leads/{id}/handoff`
   `{ "mode": "BOT" | "HUMAN" }` → 200 com o lead atualizado. Lead de outro
   lojista → 404 (cross-lojista, padrão Spec 83). Modo inválido → 400.
7. **Auditoria** — `PlatformAction` += `LEAD_HANDOFF` (junto com as ações do
   CRM — `CHAT_MESSAGE_SENT`/`LEAD_BLOCKED` da Spec 83 — na seção `// Bots
   (Spec 74)` do enum); trilha na mesma transação com actor (MERCHANT via
   portal), payload com before/after (`BOT` → `HUMAN` e vice-versa) — padrão
   Spec 41.
8. **UI (Spec 84)** — no cabeçalho do Live Chat, botão **"Pausar bot"** (quando
   `BOT`) / **"Retomar bot"** (quando `HUMAN`) com confirmação leve; em
   `HUMAN`, indicador visual claro ("Atendimento manual — bot pausado") + as
   mensagens recebidas continuam chegando via SSE; o envio do painel (Spec 83
   D5) funciona igual.
9. **Testes** — unitários/integração (domínio idempotente, interceptação
   Telegram HUMAN → persiste + SSE + sem `TELEGRAM_UPDATE`; BOT → publica;
   endpoint handoff com RBAC/404/400; auditoria) e E2E Playwright (simular
   recebimento de webhook → pausar bot → nova mensagem → persistida como
   `CLIENT` + SEM publicação do update → retomar bot → mensagem volta a
   publicar); `mvn test` verde + E2E completo sem regressão.

## Dependências

- **Specs 83/84** (CRM) — `chat_messages`, persistência no webhook, SSE +
  Redis pub/sub, Live Chat modal. **Implementar junto** (o CRM ainda não está
  implementado — status `verified`).
- **Spec 74** (integração) — webhook Telegram D5.3, listener Discord D6,
  `store_bots`.
- **Spec 75** (funil) — `bot_leads`, `recordLead`.
- **Spec 52** (bots) — o funil que será silenciado em `HUMAN`.
- **Spec 41** (auditoria) — `LEAD_HANDOFF` na mesma transação.

## Fora de escopo

- **Mídia no chat** — segue a Spec 83 (texto primeiro; mídia recebida exibida).
- **Handoff automático (ex.: por tempo ou conversão)** — manual por decisão do
  usuário; automação é evolução.
- **Fila de espera/atribuição a atendente** — o painel é do lojista (1 lojista).
- **Mudar o mecanismo de envio** — reusa a Spec 83/74.
