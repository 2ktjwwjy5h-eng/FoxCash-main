# Spec 49 — Design: Bots de venda (Discord + Telegram)

## Contexto

O backend (Spec 48) expõe: planos ativos (`GET /nyx/plans`), compra
(`POST /nyx/plans/{id}/purchase`), entregas pendentes/por-transação e confirmação
(`POST /nyx/deliveries/{id}/complete`). O FoxCash já notifica `SALE_PAID` via
webhook de saída assinado (Spec 35). Os bots são **thin clients**: zero regra de
negócio — só orquestram o fluxo e executam a ação de entrega na plataforma.

## Decisões de arquitetura

### 1. Estrutura (monorepo `bots/`)

```
bots/
  package.json            (workspaces: core, discord, telegram)
  tsconfig.base.json
  .env.example
  README.md
  core/                   (núcleo compartilhado — sem dependência de plataforma)
    src/
      foxcash-client.ts   (fetch: plans, purchase, deliveries, complete)
      webhook-server.ts   (express: POST /webhooks/foxcash, valida HMAC, dispatcher)
      signature.ts        (HMAC-SHA256 do raw body; comparador em tempo constante)
      dedup.ts            (Set de eventIds processados + janela)
      sales.ts            (orquestração: cobrança aberta → entrega)
      payload.ts          (interpreta delivery_payload e devolve ação concreta)
      config.ts           (dotenv tipado)
    test/
  discord/                (discord.js)
    src/index.ts          (client + slash commands /comprar /planos /cancelar)
    src/deliver.ts        (executa entrega: role, DM, mensagem)
  telegram/               (grammY)
    src/index.ts          (bot + /comprar /planos /cancelar)
    src/deliver.ts        (executa entrega: DM, link, mensagem)
```

### 2. Fluxo `/comprar` (comum aos dois bots)

1. Usuário envia `/comprar` → core lista `GET /nyx/plans` (cache curto 30s).
2. Plataforma exibe seleção (select menu no Discord / inline keyboard no
   Telegram).
3. Escolhido → `POST /nyx/plans/{id}/purchase` com `buyerId` =
   `sha256(plataforma + userId)` (opaco e estável por comprador — antifraude
   Spec 19) e `idempotencyKey` = `compra:{userId}:{timestamp}` (novo a cada
   `/comprar`).
4. Bot envia QR (buffer do `qrCodeBase64`) + Pix Copia e Cola + valor/plano +
   "aguardando pagamento…". O estado da compra fica num Map local
   `userId → {transactionId, planId}` (TTL ~30min).
5. **Webhook** (`POST /webhooks/foxcash`): valida assinatura (401 sem/inválida),
   dedup por `eventId`; `SALE_PAID` → `GET /nyx/deliveries/by-transaction/{tx}` →
   executa a entrega → `POST /nyx/deliveries/{id}/complete {status: DELIVERED}`;
   `SALE_REFUNDED` → notifica o usuário (se o chat estiver rastreado).
6. **Recuperação:** intervalo (30s) busca `GET /nyx/deliveries/pending` e entrega
   pendências (idempotente no backend).

### 3. Assinatura e dedup

- `signature.ts`: `crypto.createHmac('sha256', secret).update(rawBody).digest('hex')`
  comparado com `X-FoxCash-Signature` em **tempo constante**
  (`crypto.timingSafeEqual` em buffers de igual comprimento — igual ao padrão do
  backend).
- `dedup.ts`: guarda `eventId`s processados (Map com TTL, ex.: 5min); re-entrega
  é no-op. A idempotência do backend (`complete` gate de status) é o backstop.

### 4. Entrega (`payload.ts` + adapter da plataforma)

O `delivery_payload` é JSON do lojista (Spec 48). O core devolve uma **ação
tipada**; o adapter da plataforma executa:

- `{ type: "discordRole", roleId }` (só Discord): `member.roles.add(roleId)`.
- `{ type: "message", text }`: envia DM com o texto.
- `{ type: "link", url, label? }`: envia botão/embed com o link.
- Fallback: envia o JSON cru como mensagem (nunca quebra a venda).

Se a execução falhar (ex.: role inexistente, DM fechado) → marca
`FAILED {note}` — não fica pendurado; o log aponta a correção manual.

### 5. Config (`config.ts` + `.env.example`)

`NYX_BASE_URL`, `NYX_API_KEY`, `WEBHOOK_SECRET`, `WEBHOOK_PORT`, `DISCORD_TOKEN`,
`GUILD_ID`, `TELEGRAM_BOT_TOKEN`, `DELIVERY_POLL_MS` (default 30_000),
`IDEMPOTENCY_PREFIX` (default `bot`). Segredos só por env; `.env` no gitignore.

### 6. Testes

- Unit (vitest, core): cliente FoxCash (fetch mockado), `signature` (válida/
  inválida/tamper), `dedup` (re-entrega no-op), `sales` (fluxo, plano inativo,
  erro do complete), `payload` (cada tipo + fallback).
- **Smoke E2E (sem plataformas):** script `bots/scripts/smoke.ts` roda contra o
  app dev (Spec 48): cria plano (via API), purchase, simula webhook `PAID`
  assinado, verifica entrega PENDING → executa → complete → `DELIVERED`. Valida
  o contrato ponta a ponta sem tokens de plataforma.

## Arquivos (visão geral)

- `bots/package.json`, `tsconfig.base.json`, `.env.example`, `README.md`,
  `.gitignore`
- `bots/core/src/{foxcash-client,signature,dedup,sales,payload,config,webhook-server}.ts`
- `bots/discord/src/{index,deliver}.ts`
- `bots/telegram/src/{index,deliver}.ts`
- `bots/scripts/smoke.ts`
- Testes vitest em `bots/core/test/`
