# Spec 52 — Task breakdown

Status: `audited` (2026-08-06). **Ciclo SDD completo — feature LIVE.**

## Implementação

- [x] **Scaffold `bots/`** — workspaces (core/discord/telegram), tsconfig,
      .env.example, .gitignore, README.
- [x] **Core**
  - [x] `config.ts` (dotenv tipado), `storefront.ts` (fetch + cache 30s),
        `foxcash-client.ts` (plans/purchase/deliveries/actions/scheduled;
        X-Api-Key; erros tipados; backoff em 429).
  - [x] `signature.ts` (HMAC + timingSafeEqual), `dedup.ts` (eventId TTL).
  - [x] `sales.ts` (máquina de venda), `delivery-actions.ts` (executa ação +
        fila em memória com backoff), `payload.ts` (interpreta payload/botões/
        mensagens, variáveis `{nome}`/`{plano}`), `scheduler.ts` (polling
        pending + scheduled), `webhook-server.ts` (express, raw body, HMAC,
        dedup, dispatcher). **Stateless: nenhuma persistência local (sem
        SQLite/JSON) — todo estado via API do FoxCash (Spec 49).**
- [x] **Bot Discord** (discord.js) — renderiza storefront (menu/botões/planos
      embed com cor+imagem), QR + Copia e Cola, entrega (grant_role/DM/link/
      arquivo), comandos admin com ACL.
- [x] **Bot Telegram** (grammY) — inline keyboard, foto QR, DM, entrega
      (add_to_group/DM/link/arquivo), admin ACL.
- [x] **Testes (vitest, core)** — signature, dedup, client, sales, payload,
      delivery-actions (fila/backoff), store.
- [x] **Smoke E2E** (`bots/scripts/smoke.ts`) — contra app dev (Specs 48–51):
      storefront → comprar → QR → webhook PAID assinado → ações → complete →
      DELIVERED; replay idempotente; entrega após "reinício" (polling).
- [x] **README `bots/`** — setup, tokens, URL pública, registro no portal,
      contrato de assinatura.

## Verificação

- [x] `npm test` (vitest) verde; `tsc --noEmit` limpo.
- [x] Smoke E2E ponta a ponta (entrega DELIVERED; mensagens por etapa).
- [x] Backend sem regressão (specs 48–51: `mvn test` **675/0/0** + E2E).

## Verificação (`/spec-verify`, 2026-08-05)

- [x] Consistência verificada: bots **100% stateless** (sem SQLite/JSON); envio
      de `platform`/`platformUserId` no purchase; entrega e recuperação via API;
      ACL admin; fila em memória + polling como backstop.
- [x] Achados aplicados: nota de que o **dedup de `eventId` é best-effort** (em
      memória) — a idempotência real vem do backend (gate de status, Spec 49);
      sem estado crítico dependente de RAM. Status → `verified`.

## Auditoria (`/spec-audit`, 2026-08-06)

- [x] **8/8 critérios de aceite do requirements OK** — verificação completa:
      `npm test` **48/48** (7 arquivos), `tsc --noEmit` limpo (core/discord/
      telegram), smoke E2E ponta a ponta verde ("Smoke OK"), `mvn test` backend
      **675/0/0** sem regressão.
- [x] **Achado 1 corrigido — backoff em 429:** design §4 exige "respeita 429 do
      backend (backoff no client)" e o `FoxCashClient` só lançava o erro tipado
      → `request()` agora re-tenta 429 com **backoff exponencial** (500ms→1s, 2
      retries, `sleep` injetável p/ teste) antes de lançar. +2 testes.
- [x] **Achado 2 corrigido — mensagem `delivered`:** o requirements exige
      "quando todas DONE → `delivered` (mensagem) → entrega DELIVERED" e o
      `DeliveryActions` não enviava a mensagem da etapa → hook `onDelivered`
      (dispara **apenas** com 0 falhas; bots Discord/Telegram enviam a mensagem
      `delivered` do storefront com `{nome}`/`{plano}` resolvidos). +2 testes.
- [x] **Aceitos por design:** `add_to_group` no Discord e `grant_role` no
      Telegram retornam FAILED `unsupported` (Design §3); `scheduled-messages`
      devolve recipients sem plataforma (assume-se 1 plataforma por loja —
      decisão Spec 52; o executor valida os IDs). Status → `audited`.
