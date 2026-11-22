---
name: Bot ↔ Gateway — atribuição de venda (botId), provas de compra e compatibilidade com webhook v2
status: audited
---

# Spec 150 — Design: Bot ↔ Gateway

## D1 — Onde muda no bot (payload do purchase)

`SalesMachine.openSale()` (`bots/core/src/sales.ts`, linhas ~56-62) hoje monta:

```ts
{ platform, platformUserId, buyerId, idempotencyKey, expirationSeconds }
```

Passa a montar (campos novos em destaque):

```ts
{
  platform, platformUserId, buyerId, idempotencyKey, expirationSeconds,
  botId,                 // id do store_bot (config/env do bot — ver D2)
  termsAccepted: true,   // no momento da confirmação da compra (ver D3)
  buyerIp?, buyerDevice? // opcionais — quando o canal expuser
}
```

`FoxCashClient` (`bots/core/src/foxcash-client.ts`) atualiza o tipo do request
`PurchaseRequest` para incluir os campos novos (o backend já os aceita — contrato
comprovado em `e2e-tests/bot-funnel.spec.js:191-201` e `bots.spec.js:134-143`).

## D2 — De onde vem o `botId`

O bot é registrado por lojista em `store_bots` (portal — Spec 74/77/78). O
`botId` chega ao processo por **env** (`FOXCASH_BOT_ID`) na config do bot —
mesmo padrão das demais envs (`NYX_API_KEY`, `WEBHOOK_SECRET`). Telegram e
Discord usam o mesmo core: um único valor por instância do bot.

## D3 — Momento do `termsAccepted`

O `termsAccepted: true` é enviado quando o comprador **confirma** a compra (após
`/comprar` → escolha do plano → confirmação), antes de exibir o QR. O core expõe
o envio do aceite como parte do `openSale` (o canal decide quando chamar);
nunca é enviado sem ação do usuário. Sem mudança de produto visível — só o
registro da prova.

## D4 — Webhook v2 no receiver (Spec 148)

`bots/core/src/webhook-server.ts` + `signature.ts` hoje validam `X-FoxCash-Signature`
= **hex puro** do HMAC-SHA256(rawBody). Passam a aceitar os **dois formatos**:

| Formato | Exemplo | HMAC sobre |
|---|---|---|
| v1 (legado) | `<hex>` | `rawBody` |
| v2 (Spec 148) | `t=<unix>,v1=<hex>` | `"<t>.<rawBody>"` |

Decisão: detectar o formato pelo prefixo `t=`; validar `v1` do par; **rejeitar
`t` fora da janela** (ex.: > 5 min — anti-replay, recomendação da Spec 148);
comparação em tempo constante; dedup por `X-FoxCash-Event-Id` mantido (Spec 35).
Durante a janela de transição da 148 (v1/v2 por lojista), o receiver aceita
ambos; ao fim, o v1 é removido em ciclo próprio.

## D5 — Dois fluxos de criação (registro para o link de pagamento)

| Fluxo | Endpoint | Quem usa |
|---|---|---|
| Portal (manual) | `POST /api/v1/portal/charges` (+ `/charges/gift`) | lojista no painel |
| Bot | `POST /api/v1/foxcash/plans/{id}/purchase` | bot/checkout de planos |

O item futuro "link de pagamento genérico" deve **reusar o fluxo do bot ou criar
um terceiro explícito** — nunca sobrepor o contrato do portal. Registrado aqui
para a spec do link não duplicar contrato.

## D6 — Arquivos (principais)

- `bots/core/src/sales.ts` (payload do `openSale`), `bots/core/src/foxcash-client.ts`
  (tipo `PurchaseRequest`)
- `bots/core/src/webhook-server.ts` + `bots/core/src/signature.ts` (validação v1/v2)
- Config: env `FOXCASH_BOT_ID` (README de `bots/`)
- Testes: unit do payload (campos enviados), unit da assinatura (v1/v2, janela,
  body-tamper), e2e do funil com `botId`

## D7 — Gateway-agnóstico (multi-gateway)

O bot pode vender via **FoxCash** ou via **concorrente** (decisão 2026-08-20).
Para isso, a integração de cobrança vira uma **porta** com adapters:

```
SalesMachine (core) ──► GatewayClient (porta)
                         ├── FoxCashGatewayClient   (payload c/ botId/termsAccepted;
                         │     webhook SALE_PAID, assinatura v1/v2 — D1/D4)
                         └── (futuro) NyxCashGatewayClient
                               (external_id idempotente; webhook payment.confirmed,
                                X-Webhook-Signature t=,v1= — mesmo formato da 148)
```

Regras:
1. **Payload é do adapter** — `botId`/`termsAccepted`/`buyerIp`/`buyerDevice`
   (D1) entram no `FoxCashGatewayClient`; a interface `GatewayClient` expõe um
   contrato neutro (`createCharge({plan, buyer, idempotencyKey, ...})`).
2. **Assinatura é do adapter** — o receiver do webhook seleciona o validador
   por `gatewayType` (config/env `FOXCASH_GATEWAY_TYPE=foxcash` hoje; o adapter
   concorrente registra o próprio). A janela anti-replay da Spec 148 já é
   compatível com o formato da NyxCash (`t=,v1=`).
3. **Eventos por gateway** — `SALE_PAID` (FoxCash) e `payment.confirmed`
   (concorrente) mapeiam para o mesmo gatilho de entrega no core.
4. **Entrega via concorrente = decisão aberta** — o sistema de *deliveries*
   (Spec 52) é da FoxCash; vender via concorrente implica decidir se a entrega
   usa o contrato da FoxCash (cobrança espelhada) ou um contrato próprio do
   adapter. Resolver ANTES de implementar o adapter concorrente (fora do escopo
   desta spec — aqui só se garante que a porta permite).
