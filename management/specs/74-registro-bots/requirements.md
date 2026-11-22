---
name: Registro central de bots de venda (backend)
status: audited
references:
  - "management/vision.md#V53"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/69-rbac-master-stepup/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 74 — Registro central de bots de venda (backend)

## O que entrega

A **camada de gestão central** dos bots de venda (Discord + Telegram) que hoje
não existe. Os bots renderizadores (Spec 52, `bots/` — discord.js + grammY)
são config-driven: o token vem de env (`DISCORD_TOKEN`/`TELEGRAM_BOT_TOKEN` no
`bots/core/src/config.ts`), não há registro no banco, não há ciclo de vida
(pausar/reativar/editar/remover) nem atribuição por lojista. Esta spec entrega:

1. **Tabela `store_bots`** — um registro por (lojista, plataforma), com token
   **criptografado em repouso** (reuso do `CryptoConverter`, AES-256/GCM) e
   `webhook_secret` aleatório por bot (para o webhook Telegram e o endpoint
   público de updates).
2. **Máquina de estados** `REGISTERING → ACTIVE ⇄ PAUSED`, com `ERROR` quando
   uma integração falha (webhook não registra, conexão cai, token inválido).
3. **CRUD no portal** (o lojista gerencia os próprios bots — Spec 46) +
   **espelho read-only no Master Console** (visão geral de todos os lojistas,
   RBAC-aware — Spec 69).
4. **Integração Telegram real**: validação via `getMe`, registro de webhook com
   `secret_token` aleatório (a URL pública NÃO carrega `{bot_id}` adivinhável),
   verificação do update recebido, e edição de descrição/comandos
   (`setMyDescription`/`setMyCommands`).
5. **Integração Discord real**: gerenciador de conexão (gateway) com
   reconexão/backoff, `client.destroy()` no pause/remove, comandos slash
   (`PUT /applications/{id}/commands`).
6. **Auditoria** (Spec 41) e **métricas de job** (Spec 53) em toda ação de
   escrita; **soft-delete** (nunca apagar fisicamente o registro).

O renderizador dos bots (Spec 52) **não muda** nesta spec — ele continua
lendo o storefront via API; apenas passa a poder ser registrado/gerenciado pelo
painel. A Spec 76 (painel) e as Specs 77/78 (criadores) consomem esta camada.

## Contexto (por que agora)

Análise externa (2026-08-12) de um sistema similar (painel central multi-bot
Telegram/Discord com vendas via gateway e métricas de leads/vendas — PRD do
irmão do usuário). O FoxCash já tem o **motor de venda por bots** audidado
(Specs 48/50/52), mas faltava o **registro e o ciclo de vida gerenciado** —
no sistema externo isso existe, porém com falhas que **não devemos copiar**
(token em texto puro, webhook público sem segredo, pool Discord em memória sem
reconexão). Decisão do usuário (2026-08-12): implementar a mesma ideia
**melhor**, reusando o que já provamos (criptografia em repouso,
HMAC/secret_token, outbox/retry da Spec 35, RBAC da Spec 69, auditoria da Spec
41, decisão stateless da Spec 52).

## Critérios de aceite

1. **Migration `V49__store_bots.sql`**: tabela `store_bots` com
   `id UUID PK`, `merchant_id UUID NOT NULL REFERENCES merchants`,
   `platform VARCHAR CHECK IN ('TELEGRAM','DISCORD')` (UPPERCASE — o JPA mapeia
   o enum com `EnumType.STRING`, nome do enum em maiúsculas; padrão do `status`),
   `token_encrypted TEXT NOT NULL` (criptografado via `CryptoConverter`),
   `token_hash VARCHAR(64) NOT NULL UNIQUE` (SHA-256 hex, lookup por igualdade —
   IV aleatório impede busca no ciphertext, padrão `document_hash`),
   `webhook_secret_hash VARCHAR(64) NOT NULL UNIQUE`,
   `status VARCHAR CHECK IN ('REGISTERING','ACTIVE','PAUSED','ERROR')`,
   `display_name`, `username` (do `getMe`), `last_health_check_at`,
   `error_message`, `deleted_at TIMESTAMP NULL` (soft-delete),
   `created_at`/`updated_at`. Índice por `(merchant_id, platform)`.
2. **Token criptografado em repouso**: o token do bot nunca é gravado em
   texto puro; nenhum endpoint de resposta devolve o token (só máscara/últimos
   4). Reuso de `CryptoConverter` (AES-256/GCM, `v1:`) — sem novo mecanismo.
3. **Máquina de estados** no domínio: transições válidas
   `REGISTERING→ACTIVE`, `ACTIVE→PAUSED`, `PAUSED→ACTIVE`, `ACTIVE/PAUSED→ERROR`,
   qualquer → `deleted` (soft); transição inválida → `409` (code
   `invalid_bot_state`).
4. **CRUD no portal** (autenticado `ROLE_MERCHANT_PORTAL`, escopo por lojista —
   nunca cruza lojista, padrão IDOR das Specs 24/31): `POST /api/v1/portal/bots`
   (criar com token), `GET /api/v1/portal/bots`, `GET/PUT/DELETE
   /api/v1/portal/bots/{id}` (editar display_name/descrição; DELETE = soft +
   desativa integração). Pausar/reativar via `POST
   /api/v1/portal/bots/{id}/pause` e `/resume`.
5. **Espelho read-only no Master Console** (ROLE_MASTER, RBAC Spec 69):
   `GET /api/v1/master/bots` (todos os lojistas, com lojista) e
   `GET /api/v1/master/bots/{id}` — sem ações de escrita nesta spec.
6. **Telegram — conectar**: valida token via `getMe` (nome/username gravados);
   registra webhook `POST https://api.telegram.org/bot{token}/setWebhook` com
   URL `https://<host>/webhooks/telegram/{id}/{secret}` e `secret_token` — o
   segredo é aleatório (32 bytes) e **nunca** o id do bot; falha → `ERROR` com
   `error_message` e auditoria.
7. **Telegram — receber update**: `POST /webhooks/telegram/{id}/{secret}`
   público valida o segredo da URL (lookup por hash SHA-256) e o header
   `X-Telegram-Bot-Api-Secret-Token` (quando presente) antes de encaminhar;
   payload desconhecido/segredo inválido → `404` sem processar; rate limit por
   IP (padrão Spec 60); body cap (padrão Spec 63). O encaminhamento para o
   renderizador usa o padrão de eventos da Spec 35 (outbox).
8. **Telegram — pausar/remover**: chama `deleteWebhook` (falha de rede → retry
   via outbox); status `PAUSED`; updates recebidos em `PAUSED`/`ERROR` são
   descartados com log (nunca encaminhados).
9. **Discord — conectar**: registra token + Application ID; o gerenciador de
   conexão (gateway) conecta com reconexão/backoff exponencial; falha
   persistente → `ERROR`; teste de presença/comandos slash registra o estado.
10. **Discord — pausar/remover**: `client.destroy()` da instância (gerenciador
    de memória) + status `PAUSED`; reativar reconecta; remove encerra a
    conexão e faz soft-delete.
11. **Auditoria**: toda ação de escrita (create/update/pause/resume/delete/
    error) grava `PlatformAction` novo (`BOT_REGISTERED`, `BOT_UPDATED`,
    `BOT_PAUSED`, `BOT_RESUMED`, `BOT_REMOVED`, `BOT_INTEGRATION_ERROR`) com
    actor (merchant ou master) e motivo — padrão Spec 41.
12. **Testes**: unit (domínio, máquina de estados), integração REST (CRUD,
    cross-merchant → 404), E2E do fluxo completo com Telegram mockado
    (validação `getMe` + registro de webhook) — padrão da suíte existente;
    `mvn test` verde sem regressão.

## Dependências

- **Specs 48/50/52** (storefront + bots renderizadores) — base; reuso, sem
  reimplementar renderizador.
- **Spec 35** (outbox/webhooks) — eventos para o renderizador e retry de
  chamadas externas (deleteWebhook etc.).
- **Spec 69** (RBAC master) e **Spec 71** (console) — espelho read-only.
- **Spec 46** (portal) — CRUD do lojista.
- **Spec 41** (auditoria) e **Spec 53** (jobs) — trilha e métricas.
- **Spec 43/60/63** (rate limit, webhook flood, body inspection) — hardening
  do endpoint público de updates.

## Fora de escopo

- **Métricas de leads/vendas por bot** → Spec 75.
- **UI do painel de bots** (cards, modais) → Spec 76.
- **Criadores de bot** (wizard Telegram/Discord) → Specs 77/78.
- **Mudanças no renderizador** dos bots (Spec 52) — contrato de leitura do
  storefront permanece.
- **Webhook de saída para o bot** já existe (Spec 35) — sem mudança.
