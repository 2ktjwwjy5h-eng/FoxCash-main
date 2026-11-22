# FoxCash Bots (Spec 52)

Bots **renderizadores da loja white-label** (Discord + Telegram) — Node.js +
TypeScript (discord.js, grammY). São **100% orientados por configuração**: baixam
o storefront (`GET /foxcash/storefront`, Specs 48–51), renderizam o fluxo e chamam a
API do FoxCash. **Alterar a loja no painel não exige atualizar/reiniciar bots.**

**Stateless (decisão 2026-08-05):** os bots **não têm banco próprio** (sem
SQLite/JSON). O **PostgreSQL do FoxCash é a única fonte de verdade** — o bot só
conversa com a API. Pedidos, destinatários (`platform`/`platformUserId`) e
entregas ficam no backend; a recuperação pós-falha/reinício usa apenas o
**polling da API** (`GET /foxcash/deliveries/pending`).

## Estrutura

```
bots/
  core/      (@foxcash/bot-core) — sem dependência de plataforma
    src/       config · types · logger · signature · dedup · foxcash-client ·
               storefront (cache 30s) · sales · payload · delivery-actions ·
               scheduler · webhook-server
    test/      vitest (44 testes)
  discord/   (@foxcash/bot-discord) — discord.js: /comprar, botões, QR,
             entrega grant_role/DM
  telegram/  (@foxcash/bot-telegram) — grammY: /comprar inline keyboard,
             foto QR, DM
  scripts/   smoke.ts (E2E contra app dev, sem plataformas)
```

## Setup

```bash
cd bots
npm install
cp .env.example .env   # preencha (ver abaixo)
```

Node 20+. Comandos (raiz do `bots/`):

```bash
npm run typecheck        # tsc --noEmit em core+discord+telegram
npm test                 # vitest (core)
npm run start:discord    # sobe o bot Discord + webhook server
npm run start:telegram   # sobe o bot Telegram + webhook server
npm run smoke            # smoke E2E contra o app dev (Specs 48–51)
```

## Config (env) — segredos SÓ por variável de ambiente

| Variável | Obrigatória | Descrição |
|---|---|---|
| `FOXCASH_BASE_URL` | — (default `http://localhost:8080`) | URL do backend FoxCash |
| `FOXCASH_API_KEY` | **sim** | API key do lojista dono da loja (Spec 12/24) — usada em **todas** as chamadas (`X-Api-Key`); use uma chave de serviço dedicada (Spec 24) para isolar escopos |
| `WEBHOOK_SECRET` | **sim** | Segredo do webhook de saída — o bot valida o HMAC do envelope (Spec 35) |
| `WEBHOOK_PORT` | — (3000) | Porta do receiver HTTP dos webhooks de saída |
| `DELIVERY_POLL_MS` | — (15000) | Intervalo de polling de pendências (entregas + mensagens programadas) |
| `DISCORD_TOKEN` | p/ Discord | Token do bot Discord. Vazio = bot desligado |
| `GUILD_ID` | p/ `grant_role` | Servidor onde o bot entrega cargos |
| `TELEGRAM_BOT_TOKEN` | p/ Telegram | Token do bot Telegram (BotFather). Vazio = bot desligado |
| `ADMIN_DISCORD_IDS` / `ADMIN_TELEGRAM_IDS` | — | IDs (CSV) autorizados aos comandos `/admin *` (ACL) |
| `FOXCASH_BOT_ID` | — (vazio) | Id do `store_bot` (Spec 74/75) — atribui a venda ao bot (funil/métricas/ranking). Vazio = envia sem `botId` (compatibilidade em dev) |
| `FOXCASH_GATEWAY_TYPE` | — (`foxcash`) | Gateway usado pelo bot (Spec 150/D7) — hoje apenas `foxcash`; adapter concorrente registra o próprio |
| `LOG_LEVEL` | — (`info`) | `debug` \| `info` \| `warn` \| `error` (logs estruturados JSON) |

## Registro no portal (webhook de saída)

Para o backend notificar o bot em `SALE_PAID`/`SALE_REFUNDED` etc., o lojista
cadastra o webhook no portal (Spec 35) apontando para a **URL pública** do bot:

```bash
curl -s -X POST http://localhost:8080/api/v1/portal/webhooks \
  -H "Authorization: Bearer <portal-token>" -H "Content-Type: application/json" \
  -d '{"url":"https://seu-bot.example.com/webhooks/foxcash","events":["SALE_PAID","CHARGE_CREATED"]}'
# => {"id":"...","secret":"<64 hex — UMA única vez>", ...}
```

> **Exposição:** o receiver do bot escuta em `WEBHOOK_PORT` na rota
> `POST /webhooks/foxcash`. Em produção, exponha atrás de HTTPS (nginx/Caddy) —
> o backend exige URL pública. O segredo devolvido **uma única vez** vira
> `WEBHOOK_SECRET` no `.env` do bot.

**Contrato de assinatura e envelope** (headers `X-FoxCash-Signature`,
`X-FoxCash-Event-Id`, `X-FoxCash-Event`; HMAC-SHA256 do raw body): documentado
no [README raiz](../README.md) — seção de webhooks de saída (Spec 35). O bot
valida em **tempo constante** (`core/src/signature.ts`) e **deduplica** por
`eventId` em memória (best-effort — a idempotência real é do backend, Spec 49).

**Assinatura v1/v2 (Spec 148/150):** o receiver aceita os dois formatos durante
a transição — **v1** (hex puro do HMAC do raw body) e **v2**
(`t=<unix>,v1=<hex>` — HMAC de `"<t>.<rawBody>"`, janela anti-replay de 5 min
rejeita `t` velho/futuro). O formato é detectado pelo prefixo `t=`; inválido →
`401 invalid_signature`.

## Compra de plano — payload enviado (Spec 150)

`POST /api/v1/foxcash/plans/{id}/purchase` (via adapter `FoxCashGatewayClient`,
`core/src/gateway-client.ts` — o core conversa com a porta `GatewayClient`):

| Campo | Origem | Descrição |
|---|---|---|
| `platform` / `platformUserId` | bot | destinatário real (persistido criptografado) |
| `buyerId` | bot | pseudônimo `sha256(platform:userId)` (antifraude) |
| `idempotencyKey` | bot | obrigatória (Spec 28) — replay devolve a mesma cobrança |
| `botId` | env `FOXCASH_BOT_ID` | atribui a venda ao bot (Spec 75 — funil/métricas/ranking) |
| `termsAccepted` | bot (na confirmação) | grava `termsAcceptedAt` (prova de aceite) |
| `buyerIp` / `buyerDevice` | opcional | provas — o backend captura do header; bot envia só se o canal expuser |

**Dois fluxos de criação de cobrança (D5):** o **portal** usa
`POST /api/v1/portal/charges` (+ `/charges/gift`) — lojista no painel; o **bot**
usa `POST /api/v1/foxcash/plans/{id}/purchase` — checkout de planos. O link de
pagamento futuro reusa o contrato do bot (não duplica o do portal).

## Fluxo renderizado

1. `/comprar` (ou botão `buy`): storefront → menu (botões Spec 50) + cards dos
   planos (Spec 49: imagem, cor do embed, preço).
2. Escolhe plano → `POST /foxcash/plans/{id}/purchase` com `platform` +
   `platformUserId` + `buyerId` pseudônimo (`sha256(platform:userId)`) →
   **o backend persiste o destinatário** → QR + Copia e Cola + `awaiting_payment`.
3. Cliente paga → PSP confirma → FoxCash aprova → webhook `SALE_PAID` assinado →
   bot valida HMAC + dedup → `GET /foxcash/deliveries/by-transaction/{tx}` →
   executa **ação por ação** (Spec 49) → `POST .../actions/{seq}/complete` →
   todas DONE → `DELIVERED` + mensagem `delivered`.
4. **Polling** (`DELIVERY_POLL_MS`): `GET /foxcash/deliveries/pending` (recupera
   entregas mesmo após reinício do bot) e `GET /foxcash/scheduled-messages?dueBefore=`
   (envia programadas, marca `sent` — Spec 51).

Mensagens aceitam as variáveis `{nome}` e `{plano}` (Spec 51) — o bot substitui
antes de enviar.

### Ações de entrega por plataforma (Spec 49)

| Tipo | Discord | Telegram |
|---|---|---|
| `grant_role` | `member.roles.add(roleId)` | — (FAILED `unsupported`) |
| `add_to_group` | — (FAILED `unsupported`) | envia o convite (link) |
| `send_message` | DM (`{nome}`/`{plano}`) | DM |
| `send_link` | DM com botão | DM com botão inline |
| `send_file` | DM com arquivo | DM com arquivo |

Falha → `FAILED {note}` + continua as demais ações; entrega com falhas é
reaberta (`POST /deliveries/{id}/retry`) com **backoff em memória**
(1s→2s→5s→10s, máx. 5) e o **polling é o backstop** em reinício.

## Segurança

- **Auth:** API key do lojista em todas as chamadas (Spec 12/24); nada de token
  em log.
- **Webhook:** HMAC tempo constante (Spec 35); dedup por `eventId`; raw body
  preservado.
- **Payloads** de ações/botões tratados como **opacos** (nunca logados);
  chegam via API já decifrados (criptografados em repouso no backend).
- **Logs:** estruturados (JSON); `platformUserId`/payload/secret nunca logados.
- **ACL:** `/admin *` restrito a `ADMIN_*_IDS` (env).

## Testes

```bash
npm test        # vitest: signature, dedup, client, storefront, sales, payload,
                # delivery-actions (44 testes)
npm run smoke   # smoke E2E contra o app dev (Specs 48–51): storefront → comprar
                # → QR → webhook PAID assinado → ações → DELIVERED
```

Backend sem regressão: `mvn test` + E2E no diretório raiz (Specs 48–51).
