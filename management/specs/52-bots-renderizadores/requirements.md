---
name: Bots renderizadores da loja (Discord + Telegram)
status: audited
references:
  - "management/vision.md#V32"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/49-planos-entregas/requirements.md"
  - "management/specs/50-botoes-menu/requirements.md"
  - "management/specs/51-mensagens-automaticas/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
---

# Spec 52 — Bots renderizadores da loja (Discord + Telegram)

## O que entrega

Dois bots (Node.js + TypeScript: **discord.js** e **grammY**) que são
**renderizadores genéricos** do storefront (Specs 48–51): baixam a configuração
da loja, renderizam o fluxo e chamam a API do FoxCash — **100% orientado por
configuração** (mudar a loja no painel não exige atualizar/reiniciar bots).

**Fluxo renderizado:** boas-vindas (mensagem `welcome`) + menu (botões Spec 50,
na ordem) → `buy` → lista de planos (cards visuais Spec 49: imagem, cor do
embed, preço, destaque) → escolher → `POST /nyx/plans/{id}/purchase` (buyerId
opaco estável) → envia QR + Copia e Cola + `awaiting_payment` → cliente paga →
PSP confirma → FoxCash aprova → webhook `SALE_PAID` assinado → bot valida HMAC,
dedup por `eventId` → busca a entrega (`GET /nyx/deliveries/by-transaction/{tx}`)
→ executa **ação por ação** (Spec 49: grant_role/send_message/send_link/
add_to_group/send_file) → `POST .../actions/{seq}/complete` por ação → quando
todas DONE → `delivered` (mensagem) → entrega `DELIVERED`. Bot offline →
**polling** de pendências recupera e entrega (idempotente). Botões `social`/
`link`/`preview`/`faq`/`close` navegam pelo fluxo configurado.

## Critérios de aceite

- **Dois bots funcionais** (Discord + Telegram) renderizando o storefront da
  loja (dev: app local); sem hardcode de loja/fluxo no código.
- **Auth bots ↔ API:** o bot usa a **API key do lojista** (`X-Api-Key`,
  Spec 12/24) para todas as chamadas; chave de serviço dedicada se o lojista
  quiser isolar (Spec 24). **Sem JWT novo** — o padrão existente é suficiente;
  documentado.
- **Webhook receiver:** valida `X-FoxCash-Signature` (HMAC-SHA256 do raw body,
  tempo constante); 401 sem/inválida; **dedup por `eventId`** (re-entrega no-op;
  o backend também é idempotente).
- **Entrega por ações:** executa cada ação da entrega (Spec 49), confirma
  individualmente; falha → `FAILED {note}` e continua as demais; `retry` reabre
  (Spec 49). **Fila de reprocessamento:** as entregas pendentes são enfileiradas
  localmente e reprocessadas com backoff (o polling + o retry cobrem falhas
  transitórias).
- **Polling:** intervalo configurável busca `GET /nyx/deliveries/pending` e
  `GET /nyx/scheduled-messages?dueBefore=` (Spec 51) — recupera entregas e
  envia mensagens programadas (marca `sent`).
- **Persistência local do bot: NENHUMA.** Os bots são **100% stateless** — sem
  SQLite, JSON ou qualquer armazenamento persistente próprio (decisão
  2026-08-05: **PostgreSQL do FoxCash = única fonte de verdade**; só o backend
  acessa o banco). Todo o estado de pedidos, pagamentos, destinatários e
  entregas fica **centralizado no backend**:
  - no `/comprar`, o bot envia `platform` (`DISCORD`|`TELEGRAM`) +
    `platformUserId` no `purchase` (Spec 49) — o backend persiste;
  - na entrega, o backend devolve `platformUserId` — o bot executa a ação
    mesmo após reinício/substituição de instância;
  - a recuperação de pendências (bot caiu) usa apenas o **polling da API**
    (`GET /nyx/deliveries/pending`), sem estado local.
- **ACL de comandos admin:** comandos de administração do bot
  (ex.: `/admin status`) restritos por `ADMIN_DISCORD_IDS`/`ADMIN_TELEGRAM_IDS`
  (env) — 403 para demais.
- **Variáveis em mensagens:** textos do storefront aceitam `{nome}`/`{plano}` —
  o bot substitui antes de enviar.
- **Config por env:** `DISCORD_TOKEN`, `GUILD_ID`, `TELEGRAM_BOT_TOKEN`,
  `NYX_BASE_URL`, `NYX_API_KEY`, `WEBHOOK_SECRET`, `WEBHOOK_PORT`,
  `DELIVERY_POLL_MS`, `ADMIN_*_IDS`. Segredos só por env.
- **Testes:** unit (core: cliente, assinatura, dedup, payload→ação, máquina de
  ações) + **smoke E2E** contra o app dev: storefront → comprar → QR → webhook
  PAID → ações → complete (sem plataformas reais).
- **Docs:** `bots/README.md` (setup, tokens, URL pública do webhook, registro
  no portal, contrato de assinatura → README raiz).

## Dependências

- Specs 48–51 (storefront/planos/botões/mensagens) e 35 (webhook de saída)
- Stack: Node 20+/TypeScript, discord.js, grammY, express, vitest
