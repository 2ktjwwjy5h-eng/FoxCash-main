---
name: Leads e métricas por bot (backend)
status: audited
---

# Spec 75 — Design: Leads e métricas por bot

## D1 — Conceito

O funil por bot é uma **camada de observação** sobre o fluxo de venda
existente (Specs 49/52/23). Três eventos por `(bot_id, platform_user_id)`:

- `lead` — usuário inicia conversa (Telegram: update no endpoint público da
  Spec 74; Discord: interação no gerenciador).
- `checkout_started` — o `purchase` com `botId` cria a cobrança.
- `paid` — o webhook do PSP marca a transação `PAID` (Spec 23) na mesma
  transação.

Métricas derivadas: `leads` (COUNT distinct em `bot_leads`), `vendas` (COUNT
de transações `PAID` com `bot_id` e status não-estornado), `receita` (soma),
`conversao` (vendas/leads). **Nenhuma regra financeira existente é tocada** —
o funil é escrita adicional; a métrica de venda lê o status da transação
(fonte oficial).

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Lead = usuário único que inicia conversa | `bot_leads` com `UNIQUE (bot_id, platform_user_id)`; idempotente (on-conflict do-nothing) |
| Venda = pagamento confirmado | transação `PAID` não estornada (Specs 23/29) |
| Funil (não só contagem) | `bot_funnel_events` com `lead/checkout_started/paid` |
| `botId` opcional no purchase | sem `botId` → comportamento atual (zero regressão) |
| Métricas por período | default 30d; `from`/`to` opcionais |

## D2 — Persistência

### D2.1 Migration (Flyway) — `V50__bot_leads_and_funnel.sql`

Última migration conhecida: **V49** (Spec 74). Usar **V50** (verificar o
próximo número livre).

```sql
CREATE TABLE bot_leads (
    id                UUID PRIMARY KEY,
    bot_id            UUID        NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    platform_user_id  VARCHAR(255) NOT NULL,
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bot_id, platform_user_id)
);

CREATE TABLE bot_funnel_events (
    id                UUID PRIMARY KEY,
    bot_id            UUID        NOT NULL REFERENCES store_bots (id) ON DELETE CASCADE,
    platform_user_id  VARCHAR(255) NOT NULL,
    event             VARCHAR(16) NOT NULL CHECK (event IN ('lead','checkout_started','paid')),
    transaction_id    UUID        NULL REFERENCES pix_transactions (id),
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_bot_funnel_bot_time ON bot_funnel_events (bot_id, occurred_at);
CREATE INDEX ix_bot_funnel_transaction ON bot_funnel_events (transaction_id) WHERE transaction_id IS NOT NULL;
```

### D2.2 Camada JPA

- `BotLeadJpaEntity` + `BotLeadJpaRepository` (`findByBotIdAndPlatformUserId`,
  `insertIgnore`/`save` com catch de unique → no-op) + `BotLeadMapper`.
- `BotFunnelEventJpaEntity` + `BotFunnelEventJpaRepository`
  (`findByBotIdAndTransactionId`, `countByBotIdAndEvent`) + mapper.
- Portas `BotLeadRepository` e `BotFunnelEventRepository` (hexagonal) +
  adapters JPA.

## D3 — Domínio

- `BotLead` (value object imutável): `botId`, `platformUserId`, `firstSeenAt`;
  registro idempotente (`registerIfAbsent`).
- `FunnelEvent` enum: `LEAD`, `CHECKOUT_STARTED`, `PAID`.
- `BotMetrics` (value object): `leads`, `vendas`, `receita` (`Money`),
  `conversao` (double, vendas/leads; 0 quando leads = 0).

## D4 — Serviços

- `BotLeadService.recordLead(botId, platformUserId)` — idempotente; chamado
  pelo endpoint público da Spec 74 (Telegram) e pelo gerenciador Discord.
- `BotFunnelService.recordCheckout(botId, platformUserId, transactionId)` —
  chamado pelo `purchase` (Spec 49) quando `botId` presente.
- `BotFunnelService.recordPaid(transactionId)` — chamado pelo processamento
  do webhook do PSP **na mesma transação** que marca `PAID` (Spec 23);
  idempotente por `(bot_id, transaction_id)`.
- `BotMetricsService.summarize(botId, from, to)` e
  `summarizeMerchant(merchantId, from, to)` — queries de contagem/soma sobre
  `bot_leads` + `pix_transactions` (status `PAID` e não estornado);
  `summarizeAll(from, to)` para o master.

## D5 — Atribuição `botId` no purchase

`POST /api/v1/foxcash/plans/{planId}/purchase` (Spec 49/52) ganha campo **opcional**
`botId` no body:

1. Ausente → comportamento atual (zero regressão).
2. Presente → validação: `store_bots` do `botId` pertence ao lojista da API
   key (`404` senão — não revela existência) e status `ACTIVE` (`409
   bot_inactive`).
3. Grava `bot_id` na transação (`pix_transactions.bot_id` — coluna nova
   anulável) e na entrega (quando aplicável) + `recordCheckout` no funil.
4. A transação `PAID` (Spec 23) dispara `recordPaid` na mesma transação.

## D6 — Registro de lead por plataforma

- **Telegram**: `POST /webhooks/telegram/{id}/{secret}` (Spec 74 D5.3), após
  validar segredo/estado, extrai `platform_user_id` do update e chama
  `recordLead` — idempotente (replay do Telegram não duplica).
- **Discord**: o gerenciador de conexão (Spec 74 D6), ao receber interação,
  chama `recordLead`.

## D7 — REST (métricas)

| Método | Rota | Auth | Retorno |
|---|---|---|---|
| GET | `/api/v1/portal/bots/{id}/metrics?from&to` | ROLE_MERCHANT_PORTAL | `{ botId, leads, vendas, receita, conversao }` |
| GET | `/api/v1/portal/bots/metrics?from&to` | ROLE_MERCHANT_PORTAL | agregado da loja (array por bot + total) |
| GET | `/api/v1/master/bots/metrics?from&to` | ROLE_MASTER | espelho read-only (todos os lojistas, RBAC Spec 69) |

Convenções: `from`/`to` (ISO, default 30d atrás/agora); `receita` em
centavos/escala normalizada (padrão do projeto, Spec 30); respostas sem PII
adicional (só contagens/agregados).

## D8 — Consistência e segurança

- **Idempotência**: `recordLead` (UNIQUE + no-op), `recordPaid`
  (`(bot_id, transaction_id)`), `purchase` já idempotente por
  `idempotencyKey` (Spec 23).
- **Venda = PAID não estornado**: a query de métricas filtra
  `status = 'PAID'` (ou `REFUNDED` excluído) — nunca soma o funil `paid` como
  dinheiro (o funil é trilha; o valor vem da transação, fonte oficial).
- **IDOR**: métricas do portal escopadas ao lojista (cross-lojista → `404`).
- **RBAC master** (Spec 69): espelho read-only exige permissão de operação.
- **Sem PII extra**: `platform_user_id` fica no funil para dedup, mas nenhum
  endpoint de métricas o expõe.

## D9 — Auditoria

Nenhuma ação administrativa nova (métricas são leitura). O funil é trilha
auditável por natureza (append-only); `PlatformAction` não muda nesta spec.
