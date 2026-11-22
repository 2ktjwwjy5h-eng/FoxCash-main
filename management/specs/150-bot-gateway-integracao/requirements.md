---
name: Bot ↔ Gateway — atribuição de venda (botId), provas de compra e compatibilidade com webhook v2
status: audited
references:
  - "management/vision.md#V111"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/113-ranking-premiacoes-backend/requirements.md"
  - "management/specs/124-profile-data-scope/requirements.md"
  - "management/specs/125-trace-transacao/requirements.md"
  - "management/specs/148-webhooks-outbound-v2/requirements.md"
---

# Spec 150 — Bot ↔ Gateway: atribuição, provas e webhook v2

## O que entrega

Correções e preparação da integração **bot de venda ↔ gateway** (achados da
auditoria de integração 2026-08-20): o bot vende e recebe pagamento
corretamente, mas o payload do `purchase` **omite campos** que o gateway já
aceita — o que quebra atribuição de venda ao bot, provas de compra e o perfil de
operação. Esta spec (1) corrige o payload do bot, (2) prepara o receiver do bot
para o webhook v2 (Spec 148) e (3) documenta os dois fluxos de criação de
cobrança para o link de pagamento futuro.

1. **Atribuição da venda ao bot (`botId`)** — `SalesMachine.openSale()`
   (`bots/core/src/sales.ts`) passa a enviar `botId` no purchase. Efeitos:
   (a) o funil e as métricas por bot (Spec 75 — `CHECKOUT_STARTED`/`PAID`) e o
   ranking/premiações por bot (Spec 113) passam a contar as vendas via bot;
   (b) o gateway resolve o **perfil de operação** a partir do `botId`
   (`CreatePixChargeService.resolveProfileId` — Spec 124: `botId` →
   `store_bots.profile_id`), sem mudança no backend.
2. **Prova de aceite dos termos (`termsAccepted`)** — o fluxo envia
   `termsAccepted: true` no momento em que o comprador confirma a compra; o
   gateway registra `termsAcceptedAt` na cobrança (prova de entrega/compliance —
   Spec 125 e decisão Spec 49+). Sem isso, cobranças via bot não têm aceite.
3. **Provas opcionais (`buyerIp`/`buyerDevice`)** — enviados **quando o canal
   expuser** (Telegram/Discord normalmente não dão IP ao bot; o campo é
   opcional e nunca quebra o fluxo).
4. **Compatibilidade com webhook v2 (Spec 148)** — o receiver do bot
   (`bots/core/src/webhook-server.ts` + `signature.ts`) passa a aceitar o
   formato `t=<unix>,v1=<hex>` **e** o formato legado (hex puro), com janela de
   transição; quando a Spec 148 virar default `v2` por lojista, o bot já valida
   sem quebra.
5. **Documentação dos fluxos de criação** — registra que existem **dois**
   caminhos de criação (portal `POST /api/v1/portal/charges` vs bot
   `POST /api/v1/foxcash/plans/{id}/purchase`) para o item "link de pagamento"
   (futuro) reusar o contrato certo sem duplicar.
6. **Bot gateway-agnóstico (multi-gateway)** — o bot de venda é um **cliente de
   gateway plugável**: pode vender via **FoxCash** OU via **gateway concorrente**
   (ex.: NyxCash). A integração é desenhada como **adapter** (`GatewayClient`) —
   nunca acoplada a um provedor: criação de cobrança, validação de assinatura de
   webhook e mapeamento de eventos são **por adapter**. Os campos `botId`/
   `termsAccepted` são **conceitos FoxCash** e vivem no adapter FoxCash; um
   adapter concorrente usa o próprio contrato (ex.: `external_id` idempotente,
   webhook `payment.confirmed`).

## Critérios de aceite

1. **Payload corrigido** — `openSale()` envia `botId` + `termsAccepted` (e
   `buyerIp`/`buyerDevice` quando disponíveis); a cobrança criada via bot fica
   com `bot_id` preenchido e `terms_accepted_at` gravado.
2. **Funil e ranking** — vendas via bot disparam `CHECKOUT_STARTED`/`PAID` por
   bot (Spec 75) e entram no ranking (Spec 113); verificado por e2e com o
   contrato atual (`bot-funnel.spec.js`).
3. **Perfil de operação** — a cobrança via bot usa o perfil do bot (Spec 124)
   sem mudança no backend (resolução por `botId`).
4. **Webhook v2** — o receiver aceita `t=,v1=` (HMAC sobre `"<t>.<body>"`) e o
   legado (hex) durante a janela; inválida → 401; dedup por `eventId` mantido.
5. **Docs** — README de `bots/` documenta os campos enviados no purchase e a
   validação de assinatura (v1/v2); testes (unit do core + e2e funnel) verdes.

## Dependências

- Spec 52 (spec atual dos bots — renderizadores orientados por configuração).
- Spec 35 (webhook outbound — assinatura/eventos que o bot consome).
- Spec 75 (funil por bot), Spec 113 (ranking/premiações).
- Spec 124 (perfil de operação — resolução por `botId`).
- Spec 125 (trace — `termsAcceptedAt` como prova).
- Spec 148 (webhook v2 — formato `t=,v1=` que o receiver precisa validar).

## Observações

- **Escopo:** exclusivamente `bots/` (core + telegram + discord) + docs; **sem
  mudança no backend** (o gateway já aceita os campos — comprovado no e2e).
- **`termsAccepted` é momento de produto:** deve ser enviado quando o comprador
  **confirma** a compra no bot (ex.: após escolher o plano e antes de mostrar o
  QR) — nunca automático sem ação do usuário.
- **Fora de escopo:** link de pagamento genérico (item de produto futuro — a
  nota D5 documenta o terreno); cartão/boleto (decisão: PIX-only).
- **Multi-gateway (decisão do usuário 2026-08-20):** o bot pode usar tanto a
  FoxCash quanto a concorrência — a Spec 150 é implementada com a abstração de
  `GatewayClient` (D7) para que nenhum fix de payload/assinatura fique acoplado
  a um provedor; o adapter FoxCash é o primeiro a ser entregue.
