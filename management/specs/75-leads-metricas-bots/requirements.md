---
name: Leads e métricas por bot (backend)
status: audited
references:
  - "management/vision.md#V53"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/49-bots-venda-discord-telegram/requirements.md"
  - "management/specs/52-bots-renderizadores/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
  - "management/specs/29-consistent-refund/requirements.md"
---

# Spec 75 — Leads e métricas por bot (backend)

## O que entrega

Rastreamento de **leads** e **métricas de venda por bot**, sobre o registro
central da Spec 74. O sistema externo analisado (PRD do irmão do usuário)
conta "lead = usuário único que inicia conversa" e "venda = pagamento
confirmado" — mas **sem funil e sem atribuição confiável por bot**. Esta spec
entrega o mesmo conceito **com funil** e **atribuição `bot_id` na cobrança**:

1. **Tabela `bot_leads`** — usuário único que inicia conversa com o bot,
   com constraint `UNIQUE (bot_id, platform_user_id)` (padrão da ideia
   externa, mas validado no backend).
2. **Funil por bot** — eventos `lead → checkout_started → paid` por
   (bot, usuário): dá para medir **conversão** (leads que pagaram), não só
   contagens soltas.
3. **Atribuição `bot_id` na cobrança** — o `purchase` (Spec 49/52) passa a
   aceitar `botId` opcional; a transação Pix e a entrega guardam o `bot_id`
   (coluna anulável, sem quebrar contrato existente).
4. **Endpoints de métricas** — para o portal (bots do lojista) e espelho no
   Master Console: leads, vendas `PAID`, conversão, por bot/loja/período.
5. **Estorno/chargeback não conta como venda** — regra dos Specs 23/29:
   venda = transação `PAID` não estornada.

O renderizador (Spec 52) e o registro (Spec 74) **não mudam de contrato**: o
`botId` no `purchase` é **opcional** (sem ele, comportamento atual); a
contagem de lead para Telegram sai do endpoint público da Spec 74 (D5.3) e
para Discord do gerenciador de conexão (Spec 74 D6).

## Contexto (por que agora)

A Spec 74 (registro central) cria a identidade do bot no banco; sem esta spec,
não há como responder "quantos leads e vendas esse bot teve" — a métrica
fundamental do painel (Spec 76) e dos criadores (Specs 77/78). A análise do
sistema externo mostrou o conceito (contagens por bot), mas com fragilidades
que **não devemos copiar**: lead sem dedup confiável no fluxo (só no schema),
venda contada por status simples, sem funil e sem tratamento de estorno.
Decisão do usuário (2026-08-12): implementar com funil e com a consistência
financeira que o FoxCash já tem (Specs 22/23/29).

## Critérios de aceite

1. **Migration `V50__bot_leads_and_funnel.sql`**: tabela `bot_leads`
   (`id`, `bot_id UUID NOT NULL REFERENCES store_bots ON DELETE CASCADE`,
   `platform_user_id VARCHAR NOT NULL`, `first_seen_at`, `created_at`,
   `UNIQUE (bot_id, platform_user_id)`); tabela `bot_funnel_events`
   (`id`, `bot_id`, `platform_user_id`, `event VARCHAR CHECK IN
   ('lead','checkout_started','paid')`, `transaction_id UUID NULL`,
   `occurred_at`, índice `(bot_id, occurred_at)`).
2. **Registro de lead (Telegram)**: o endpoint público
   `POST /webhooks/telegram/{id}/{secret}` (Spec 74 D5.3) registra o lead
   (`bot_id` + `platform_user_id` do update) de forma **idempotente** —
   replay do update não duplica (UNIQUE + on-conflict do-nothing).
3. **Registro de lead (Discord)**: a chegada de interação no gerenciador de
   conexão (Spec 74 D6) registra o lead da mesma forma.
4. **`purchase` com `botId`**: `POST /api/v1/foxcash/plans/{planId}/purchase`
   aceita `botId` **opcional**; quando presente: pertence ao lojista da API
   key (senão `404`), status `ACTIVE` (senão `409`); grava `bot_id` na
   transação/entrega e o evento `checkout_started` no funil.
5. **Evento `paid`**: o processamento do webhook do PSP (Spec 23) que marca a
   transação `PAID` com `bot_id` grava o evento `paid` no funil **na mesma
   transação** (idempotente — replay do webhook não duplica).
6. **Estorno não desfaz lead, mas desfaz venda**: transação estornada
   (REFUNDED/chargeback, Specs 23/29) deixa de contar como venda nas
   métricas; o evento `paid` permanece como histórico (a métrica filtra
   `status != REFUNDED`).
7. **Métricas — portal** (ROLE_MERCHANT_PORTAL, escopo por lojista):
   `GET /api/v1/portal/bots/{id}/metrics?from=&to=` → `{ leads, vendas, receita,
   conversao }` e `GET /api/v1/portal/bots/metrics` (agregado da loja);
   período default 30d; conversão = vendas/leads.
8. **Métricas — master** (ROLE_MASTER, espelho read-only, RBAC Spec 69):
   `GET /api/v1/master/bots/metrics` (todos os lojistas, com filtros).
9. **Sem regressão**: `purchase` sem `botId` mantém comportamento atual;
   `mvn test` verde; E2E do funil com Telegram/Discord mockados.
10. **Auditoria**: nenhuma ação administrativa nova (métricas são leitura);
     os eventos de funil são gravados pelo fluxo (não por ação de master).

## Dependências

- **Spec 74** (registro central) — `store_bots`, endpoint público de updates
  (Telegram), gerenciador de conexão (Discord).
- **Spec 49/52** (purchase/entregas) — contrato do `purchase` e da entrega.
- **Specs 22/23/29** (wallet/idempotência/estorno) — consistência do `paid`
  e exclusão de estornados.
- **Specs 46/71** (portal/console) — consumidores dos endpoints de métricas.
- **Spec 41** (auditoria) — nenhuma escrita nova, mas o funil é trilha
  auditável por natureza.

## Fora de escopo

- **UI do painel** (cards com leads/vendas) → Spec 76.
- **Criadores de bot** → Specs 77/78.
- **Mudar a contabilidade** (wallet/ledger, Specs 22/29) — apenas leitura +
  evento de funil.
- **Filtros complexos** (segmentação por usuário, atribuição de afiliado) —
  fora do escopo desta spec.
