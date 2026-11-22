---
name: Bots de venda (Discord + Telegram) — SUPERSEDED pela Spec 52 (bots renderizadores orientados por configuração)
status: discarded
references:
  - "management/vision.md#V32"
  - "management/specs/48-planos-entregas-backend/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
---

# Spec 49 — Bots de venda (Discord + Telegram) com entrega automática de acesso

## O que entrega

Dois **bots de venda** (Node.js + TypeScript) que vendem os planos do lojista
via Pix, ponta a ponta, sem intervenção humana:

- **Bot do Discord** (discord.js) e **bot do Telegram** (grammY), com um núcleo
  compartilhado (`bots/core`) que conversa com a API do FoxCash (Spec 48) e
  recebe o webhook de saída assinado (Spec 35).
- **Fluxo de venda:** `/comprar` → lista os planos ativos (`GET /nyx/plans`) →
  comprador escolhe → bot cria a cobrança (`POST /nyx/plans/{id}/purchase`, com
  `buyerId` opaco estável por comprador) → envia o **QR Code + Pix Copia e
  Cola** → cliente paga → PSP confirma → FoxCash aprova e publica `SALE_PAID`
  → o **webhook receiver** do bot recebe o evento assinado, **verifica o HMAC**,
  **deduplica por `eventId`** → busca a entrega pendente
  (`GET /nyx/deliveries/by-transaction/{tx}`) → **executa a entrega** (interpreta
  o `delivery_payload`: atribui a role no Discord, envia a chave/link no DM,
  etc.) → confirma `POST /nyx/deliveries/{id}/complete {status: DELIVERED}`.
- **Robustez:** se o bot estiver fora quando o pagamento chegar, as entregas
  pendentes são recuperadas (`GET /nyx/deliveries/pending` em intervalos) — o
  `complete` é idempotente (gate de status no backend).

## Critérios de aceite

- **Dois bots funcionais** (Discord e Telegram) com o fluxo `/comprar` completo
  contra a API da Spec 48 (dev: app local). Comandos: `/comprar`, `/planos` (e
  `/cancelar` cancela uma cobrança aberta do usuário, se viável).
- **Webhook receiver** (HTTP) valida `X-FoxCash-Signature` (HMAC-SHA256 do raw
  body com o secret do webhook) — evento sem assinatura/inválida → 401; processa
  `SALE_PAID`/`SALE_REFUNDED`; **dedup por `eventId`** (re-entrega não duplica a
  entrega; o backend também é idempotente).
- **Entrega automática:** ao `SALE_PAID`, o bot busca a entrega, executa a ação
  conforme o `delivery_payload` (ex.: `{discordRoleId}` → atribui a role ao
  comprador mapeado; `{message}` → envia DM) e marca `DELIVERED`. Falha →
  `FAILED` + `note` (não fica pendurado).
- **Recuperação:** job periódico (ex.: 30s) busca `deliveries/pending` e entrega
  o que ficou pendente (bot fora no momento do pagamento).
- **Config por env:** `DISCORD_TOKEN`, `TELEGRAM_BOT_TOKEN`, `NYX_BASE_URL`,
  `NYX_API_KEY` (do lojista), `WEBHOOK_SECRET`, `WEBHOOK_PORT`, `GUILD_ID`,
  `DISCORD_ROLE_PER_PLAN?` — sem segredos no código.
- **Testes:** unit do core (cliente FoxCash, verificação de assinatura, dedup,
  interpretação de payload) e um **smoke E2E** contra o app dev: plano →
  purchase → webhook PAID → entrega → complete (via API, sem plataforma real).
- **Documentação:** README em `bots/` com setup (tokens, ngrok/URL pública do
  webhook, registro no portal), e o contrato de assinatura apontando para o
  README raiz.

## Dependências

- `management/specs/48-planos-entregas-backend/requirements.md` (planos, purchase,
  deliveries)
- `management/specs/35-outbound-webhook/requirements.md` (assinatura, eventos)
- Stack: Node.js 20+ / TypeScript; `discord.js`, `grammY`, `express` (receiver),
  `node:crypto` (HMAC); testes com `vitest`.
