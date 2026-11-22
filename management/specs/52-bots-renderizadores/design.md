# Spec 52 — Design: Bots renderizadores (Discord + Telegram)

## Decisões de arquitetura

### 1. Estrutura (monorepo `bots/`)

```
bots/
  package.json            (workspaces: core, discord, telegram)
  tsconfig.base.json, .env.example, .gitignore, README.md
  core/                   (sem dependência de plataforma)
    src/
      storefront.ts       (GET /nyx/storefront + cache 30s; resolve loja)
      foxcash-client.ts   (fetch tipado: plans, purchase, deliveries, actions,
                           scheduled-messages; X-Api-Key; erros 404/409/429)
      signature.ts        (HMAC-SHA256 do raw body; timingSafeEqual)
      dedup.ts            (Map eventId→ttl)
      sales.ts            (máquina de estados da venda: abrir cobrança → aguardar
                           → SALE_PAID → entregar; transição por ação)
      delivery-actions.ts (executa ação → resultado; fila de reprocessamento
                           com backoff)
      payload.ts          (interpreta delivery_payload/button/message)
      scheduler.ts        (polling de deliveries/pending + scheduled-messages)
      webhook-server.ts   (express POST /webhooks/foxcash: raw body, HMAC,
                           dedup, dispatcher SALE_PAID/SALE_REFUNDED)
      config.ts           (dotenv tipado)
    test/
  discord/  src/index.ts (client + interações: menu, botões, embed de plano,
                          QR, entrega grant_role/DM) · deliver.ts
  telegram/ src/index.ts (grammY: /comprar inline keyboard, foto QR, DM) ·
                          deliver.ts
  scripts/smoke.ts       (E2E contra app dev, sem plataformas)
```

> **Stateless (decisão 2026-08-05):** o monorepo **não tem nenhuma persistência
> local** (sem SQLite/JSON). Todo o estado (pedido, destinatário, entregas) vive
> no **PostgreSQL do FoxCash** — o bot envia `platform`/`platformUserId` no
> `purchase` (Spec 49) e recebe o `platformUserId` de volta na entrega para
> executar a ação, mesmo após reinício. A recuperação pós-falha usa só a API.
> O **dedup de `eventId` é em memória (best-effort)** — se o bot reiniciar, ele
> re-processa um evento já visto; isso é seguro porque o **backend é idempotente**
> (gate de status por ação, Spec 49): re-entregar não duplica efeito.

### 2. Fluxo renderizado (comum)

1. `/comprar` (ou toque no botão `buy`): `storefront.ts` devolve botões/planos/
   mensagens; plataforma renderiza o menu (Spec 50) e os cards (Spec 49).
2. Escolhe plano → `sales.ts`: `buyerId = sha256(plataforma + userId)`,
   `idempotencyKey = compra:{userId}:{ts}` → `purchase(planId, {platform,
   platformUserId, buyerId, idempotencyKey})` → **o backend persiste o
   destinatário** (Spec 49) → envia QR (buffer) + Copia e Cola +
   `awaiting_payment`. **Nada é guardado no bot.**
3. Webhook `SALE_PAID` → `signature.ts` (401 se inválida) → `dedup.ts`
   (no-op se repetido) → `deliveryByTransaction(tx)` (o backend devolve
   `platform`/`platformUserId` + ações) → para cada ação PENDING: executa
   (`delivery-actions.ts`) e `POST /actions/{seq}/complete`; sucesso em todas →
   `delivered` (mensagem) → `DELIVERED`.
4. Polling (`scheduler.ts`): busca `deliveries/pending` (devolve
   `platformUserId` — recupera mesmo após reinício/substituição do bot) e
   `scheduled-messages ?dueBefore=now` a cada `DELIVERY_POLL_MS` — envia
   programadas (marca `sent`).

### 3. Ações de entrega (adapter por plataforma)

| Tipo | Discord | Telegram |
|---|---|---|
| `grant_role` | `member.roles.add(roleId)` | — (invalida: retorna FAILED `unsupported`) |
| `add_to_group` | — (FAILED) | `bot.api.getChatMember`/link (documenta) |
| `send_message` | DM com texto (variáveis `{nome}`/`{plano}`) | DM |
| `send_link` | DM/embed com botão | DM com link |
| `send_file` | DM com arquivo (fetch do `fileUrl`) | DM |

Falha → `FAILED {note}` + continua as demais ações; `retry` (Spec 49) reabre.

### 4. Segurança / cross-cutting (transversais, formais)

- **Auth:** API key do lojista em todas as chamadas (Spec 12/24); nada de token
  em log.
- **Webhook:** HMAC tempo constante; dedup; raw body preservado.
- **Rate limit:** respeita 429 do backend (backoff no client).
- **Criptografia:** payloads tratados como opacos no bot (nunca logados);
  `platformUserId`/payload chegam via API (criptografados em repouso no
  backend) e são usados apenas em memória para executar a ação.
- **Logs/auditoria:** logs estruturados (JSON, level configurável); nenhum dado
  sensível (payload, token, secret, platformUserId) logado.
- **Validação:** toda entrada da plataforma/API validada (zod) antes de usar.
- **ACL admin:** comandos `/admin *` restritos por `ADMIN_*_IDS`.
- **Falhas/filas:** entregas pendentes enfileiradas em memória com backoff
  exponencial (retry 1s→2s→5s→10s, máx. 5) + polling da API como backstop — em
  caso de reinício, o polling re-busca as pendências no backend (stateless).

### 5. Config (env) e docs

`DISCORD_TOKEN`, `GUILD_ID`, `TELEGRAM_BOT_TOKEN`, `NYX_BASE_URL`, `NYX_API_KEY`,
`WEBHOOK_SECRET`, `WEBHOOK_PORT`, `DELIVERY_POLL_MS`, `ADMIN_DISCORD_IDS`,
`ADMIN_TELEGRAM_IDS`. README com setup e
contrato de assinatura (link para o README raiz).

## Arquivos

- Monorepo `bots/` conforme a árvore acima; testes vitest em `core/test/`;
  `scripts/smoke.ts`.
