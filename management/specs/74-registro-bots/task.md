---
name: Registro central de bots de venda (backend)
status: audited
---

# Spec 74 — Task: Registro central de bots de venda

## F0 — Domínio + migration (critérios 1–3)

- [x] T0.1: Migration `V49__store_bots.sql` (D2.1) — **checar o próximo número
      livre** em `src/main/resources/db/migration` (V48 é a última conhecida).
      Tabela + índices (`ix_store_bots_merchant`); constraints de status e
      platform; `token_hash`/`webhook_secret_hash` UNIQUE.
- [x] T0.2: `StoreBotJpaEntity` (coluna `token_encrypted` com `CryptoConverter`)
      + `StoreBotJpaRepository` + `StoreBotMapper` (D2.2).
- [x] T0.3: Porta `StoreBotRepository` (hexagonal) + adapter JPA
      (`findByMerchantId`, `findByTokenHash`, `findByWebhookSecretHash`).
- [x] T0.4: Domínio `StoreBot` + enums `BotPlatform`/`BotStatus` (D3) —
      máquina de estados (`activate`/`pause`/`resume`/`markError`/`remove`),
      `InvalidBotStateException` → `409 invalid_bot_state` no handler.
- [x] T0.5: `tokenMask()` (últimos 4) — token nunca exposto pelo aggregate.
- [x] T0.6: Testes — `StoreBotTest` (transições válidas/inválidas), migration
      aplica limpa (flyway), UNIQUE de token_hash.

## F1 — Services (critérios 3, 11)

- [x] T1.1: `StoreBotCommandService` (D4) — `register` (serialização por UNIQUE
      de `token_hash` + `findByTokenHash` → 409 e conversão de
      `DataIntegrityViolationException` → 409 no replay concorrente — replay
      executa 1×, nunca 500), `update`, `pause`, `resume`, `remove` (soft);
      auditoria na mesma transação (D9).
- [x] T1.2: `StoreBotQueryService` — listagem (filtro platform/status, paginação
      padrão do projeto), detalhe **sem token**.
- [x] T1.3: `PlatformAction` += `BOT_REGISTERED`, `BOT_UPDATED`, `BOT_PAUSED`,
      `BOT_RESUMED`, `BOT_REMOVED`, `BOT_INTEGRATION_ERROR` (D9) + testes do
      enum.
- [x] T1.4: Testes unit/integração dos services (transições + auditoria +
      lock).

## F2 — Integração Telegram (critérios 6–8)

- [x] T2.1: Porta `TelegramBotApi` + adapter `HttpTelegramBotApiClient` (D5.1)
      — `getMe`/`setWebhook`/`deleteWebhook`/`setMyDescription`/
      `setMyShortDescription`/`setMyCommands`/`getWebhookInfo`; timeout/retry.
- [x] T2.2: `StoreBotIntegrationService.integrate` (Telegram) — `getMe` →
      grava nome/username → `setWebhook` com URL
      `/webhooks/telegram/{id}/{secret}` (secret 32 bytes) + `secret_token`;
      falha → `markError` + auditoria `BOT_INTEGRATION_ERROR`.
- [x] T2.3: `disintegrate` (Telegram) — `deleteWebhook` com retry via outbox
      (Spec 35); updates recebidos em `PAUSED`/`ERROR` são descartados (log).
- [x] T2.4: Controller público `POST /webhooks/telegram/{id}/{secret}` (D5.3):
      lookup por `webhook_secret_hash` (constant-time), header
      `X-Telegram-Bot-Api-Secret-Token` validado, descarte em PAUSED/ERROR,
      publica evento no outbox para o renderizador; `404` silencioso.
- [x] T2.5: Mock de contrato do `TelegramBotApi` (padrão
      `PayoutProviderContractTest`) + testes do endpoint (segredo inválido →
      404; PAUSED → descarta; validação do corpo).

## F3 — Integração Discord (critérios 9–10)

- [x] T3.1: `DiscordBotManager` (D6) — conexão de gateway com
      reconexão/backoff exponencial + heartbeat; `connect`/`disconnect`;
      kill-switch `foxcash.bots.discord-gateway-enabled` (off em dev/test).
- [x] T3.2: `integrate` (Discord) — registra token + Application ID, conecta,
      testa presença/comandos slash (`PUT /applications/{id}/commands`);
      falha persistente → `ERROR`.
- [x] T3.3: `disintegrate` (Discord) — `client.destroy()` + remove da memória;
      reativar reconecta.
- [x] T3.4: Testes do manager (conexão mockada, reconexão, destroy idempotente).

## F4 — REST portal + master (critérios 4–5)

- [x] T4.1: `PortalBotController` — `POST/GET/GET{id}/PUT/DELETE
      /api/v1/portal/bots` + `POST .../{id}/pause` e `/resume`
      (ROLE_MERCHANT_PORTAL; escopo por lojista — cross-lojista → 404;
      respostas sem token; `409 invalid_bot_state`).
- [x] T4.2: `MasterBotController` — `GET /api/v1/master/bots` e
      `GET /api/v1/master/bots/{id}` (ROLE_MASTER, espelho read-only, RBAC
      Spec 69 — sem escrita).
- [x] T4.3: Segurança — rotas no `SecurityConfig` (portal/master); endpoint
      público do webhook fora do filtro autenticado (D5.3/D8) + rate limit por
      IP (Spec 60) + body cap (Spec 63).
- [x] T4.4: Testes REST — CRUD feliz, validação 400, cross-lojista 404,
      transições inválidas 409, master read-only (403 em escrita), webhook 404
      em segredo inválido.

## F5 — Job de reconciliação + fechamento (critérios 11–12)

- [x] T5.1: Job `foxcash-bot-reconcile` (ShedLock + `JobExecutionService`,
      Spec 53; cron configurável; kill-switch `foxcash.bots.reconcile-enabled`):
      `REGISTERING` preso → `ERROR`; `ACTIVE` sem health recente → re-verifica;
      métricas `nyx_bot_*`.
- [x] T5.2: `BotProperties` + binding contra o `application.yml` real (padrão
      Spec 68/L16).
- [x] T5.3: E2E do fluxo completo com Telegram mockado (getMe + setWebhook +
      update → evento no outbox) — padrão da suíte E2E.
- [x] T5.4: `mvn test` verde sem regressão + lint/tsc dos frontends (se tocados).

## Auditoria (`/spec-audit` 2026-08-12)

- [x] T6.1: `/spec-audit 74` — 12/12 critérios OK; 2 achados corrigidos:
      (1) CRÍTICO — `register` não convertia a violação da UNIQUE de
      `token_hash` em 409 no replay concorrente (o `SELECT ... FOR UPDATE` da
      Spec 49 não se aplica a INSERT — o mecanismo real é UNIQUE + catch de
      `DataIntegrityViolationException` → `StoreBotAlreadyExistsException`;
      +teste `registerWithConcurrentReplayThrows409InsteadOf500`); (2) DOC —
      requirements critério 1 e design D2.1 especificavam CHECK de platform em
      lowercase e `token_hash TEXT`; a implementação usa UPPERCASE (exigido
      pelo `EnumType.STRING`) e `VARCHAR(64)` (padrão `document_hash`) —
      documentos alinhados ao código. `mvn test` verde (suíte completa) +
      E2E `bots-registro.spec.js` 5/5 (re-executado na implementação).
