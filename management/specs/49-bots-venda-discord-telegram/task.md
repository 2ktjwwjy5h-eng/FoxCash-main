# Spec 49 — Task breakdown

Status: `created` (2026-08-05). Aguardando `/spec-verify`.

## Implementação

- [ ] **Scaffold do monorepo `bots/`**
  - [ ] `package.json` (workspaces core/discord/telegram), `tsconfig.base.json`,
        `.gitignore` (node_modules, .env, dist), `.env.example`, `README.md`.
  - [ ] `config.ts` (dotenv tipado — NYX_BASE_URL, NYX_API_KEY, WEBHOOK_SECRET,
        WEBHOOK_PORT, DISCORD_TOKEN, GUILD_ID, TELEGRAM_BOT_TOKEN,
        DELIVERY_POLL_MS, IDEMPOTENCY_PREFIX).
- [ ] **Core**
  - [ ] `foxcash-client.ts`: `listPlans()`, `purchase(planId, {buyerId,
        expirationSeconds, idempotencyKey})`, `deliveryByTransaction(tx)`,
        `pendingDeliveries()`, `completeDelivery(id, status, note?)` — fetch com
        `X-Api-Key`, erros tipados (404/409/429).
  - [ ] `signature.ts`: HMAC-SHA256 do raw body + `timingSafeEqual`.
  - [ ] `dedup.ts`: Map de eventIds com TTL; `seen(id)` / `mark(id)`.
  - [ ] `webhook-server.ts`: express `POST /webhooks/foxcash` — raw body,
        401 sem/inválida, dedup, dispatcher por `type` (SALE_PAID/SALE_REFUNDED).
  - [ ] `sales.ts`: estado de compras abertas (userId → {tx, planId}) e
        orquestração SALE_PAID → entrega → complete.
  - [ ] `payload.ts`: parse do `delivery_payload` → ação tipada
        (discordRole/message/link/fallback).
- [ ] **Bot Discord** (discord.js)
  - [ ] Slash commands `/comprar` (select de planos), `/planos`, `/cancelar`.
  - [ ] Envio do QR (buffer) + Copia e Cola no canal/DM.
  - [ ] `deliver.ts`: executa ações (role via `member.roles.add`, DM de mensagem/
        link) e marca DELIVERED/FAILED; polling de `pendingDeliveries`.
- [ ] **Bot Telegram** (grammY)
  - [ ] `/comprar` (inline keyboard), `/planos`, `/cancelar`.
  - [ ] Envio da foto do QR + texto do Copia e Cola.
  - [ ] `deliver.ts`: executa ações (DM/link) e marca DELIVERED/FAILED; polling.
- [ ] **Testes (vitest, core)**
  - [ ] `signature.test.ts` (válida/inválida/tamper), `dedup.test.ts`
        (re-entrega no-op), `foxcash-client.test.ts` (fetch mockado),
        `sales.test.ts` (fluxo, plano inativo, falha do complete),
        `payload.test.ts` (tipos + fallback).
- [ ] **Smoke E2E** (`bots/scripts/smoke.ts`)
  - [ ] Contra o app dev (Spec 48): cria plano → purchase → webhook PAID
        assinado → entrega PENDING → executa → complete → DELIVERED; replay
        idempotente.
- [ ] **README** `bots/`: setup (tokens, URL pública do webhook, registro no
      portal), contrato de assinatura (aponta para o README raiz).

## Verificação

- [ ] `npm test` em `bots/` (vitest) verde; `tsc --noEmit` limpo.
- [ ] Smoke E2E ponta a ponta contra o app dev (Spec 48) — entrega DELIVERED.
- [ ] Backend sem regressão (specs 48 e anteriores: `mvn test` + E2E 22/22).
