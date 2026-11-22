---
name: Bot ↔ Gateway — atribuição de venda (botId), provas de compra e compatibilidade com webhook v2
status: audited
---

# Spec 150 — Task: Bot ↔ Gateway

Ordem: T0 (abstração de gateway) → T1 (payload) → T2 (config botId) → T3
(webhook v2) → T4 (e2e) → T5 (docs).

## T0 — Abstração GatewayClient (decisão multi-gateway)

- [x] T0.1: Porta `GatewayClient` (contrato neutro de criação) + adapter
      `FoxCashGatewayClient` — o payload atual (D1: `botId`/`termsAccepted`/
      `buyerIp`/`buyerDevice`) passa a viver NO adapter FoxCash, não no core.
- [x] T0.2: Config `FOXCASH_GATEWAY_TYPE` (default `foxcash`; hook para adapter
      concorrente); o receiver seleciona o validador de assinatura por tipo.
- [x] T0.3: Unit — core conversa com a interface (mock adapter); payload FoxCash
      isolado no adapter.

## T1 — Payload do purchase (critérios 1–3)

- [x] T1.1: `sales.ts` `openSale()` envia `botId` + `termsAccepted` (e
      `buyerIp`/`buyerDevice` quando disponíveis).
- [x] T1.2: `foxcash-client.ts` — tipo `PurchaseRequest` com os campos novos.
- [x] T1.3: Unit: payload montado contém os campos (e o `termsAccepted` só após
      confirmação do usuário — ver D3).

## T2 — Config do botId (critérios 1 e 3)

- [x] T2.1: Env `FOXCASH_BOT_ID` lida no core (fallback: sem o valor, envia sem
      `botId` — mantém compatibilidade em dev).
- [x] T2.2: Telegram e Discord passam o valor da env ao core.

## T3 — Webhook v2 no receiver (critério 4)

- [x] T3.1: `signature.ts` valida os dois formatos (hex puro e `t=,v1=`) com
      HMAC sobre `"<t>.<body>"` no v2; janela anti-replay (5 min) rejeita `t`
      velho; comparação em tempo constante.
- [x] T3.2: `webhook-server.ts` usa a validação nova; dedup por `X-FoxCash-Event-Id`
      mantido; inválida → 401.
- [x] T3.3: Unit: v1 válido, v2 válido, v2 com body-tamper, `t` fora da janela.

## T4 — E2E (critérios 2 e 3)

- [x] T4.1: E2E do funil (`bot-funnel.spec.js`) com `botId` — CHECKOUT_STARTED/PAID
      por bot disparam e a cobrança fica com `bot_id`/`terms_accepted_at`.
      (Já existia e valida o backend; o smoke `npm run smoke` validou o fluxo do
      bot contra o app dev — storefront → compra → SALE_PAID → entrega.)

## T5 — Docs (critério 5)

- [x] T5.1: README de `bots/` — campos enviados no purchase, env `FOXCASH_BOT_ID`,
      validação de assinatura v1/v2 (janela) e os dois fluxos de criação (D5).
- [x] T5.2: Suíte dos bots verde (unit + e2e) e `scripts/check-docs-consistency.sh` OK.
