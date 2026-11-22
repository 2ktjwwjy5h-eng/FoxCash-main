---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (backend)
status: verified
references:
  - "management/vision.md#V85"
  - "management/specs/50-botoes-menu/requirements.md"
  - "management/specs/51-mensagens-automaticas/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/109-links-filtro de tráfego-backend/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 117 — Automações avançadas: Meus Fluxos, Redirecionadores e Remarketing (backend)

## O que entrega

Backend das **Automações** do portal (descrição de produto do usuário
2026-08-15) — **Meus Fluxos** (funis de conversação), **Redirecionadores**
(links inteligentes — reuso da Spec 109) e **Remarketing** (recuperação de
carrinhos).

1. **Meus Fluxos** — `bot_flows` (V66): `id, merchant_id, name, status,
   trigger (MESSAGE/BUTTON — reuso Spec 50/51), node_order, created_at` +
   **`bot_flow_nodes`**: `id, flow_id, type (MESSAGE/SEND_LINK/ADD_TO_GROUP/
   GRANT_ROLE — reuso ações da Spec 49), content, sort` — um **funil de
   conversação configurável** (nós de mensagem/ação), armazenado como dados
   (o bot renderiza — padrão config-driven da Spec 52).
   - Endpoints: `GET/POST/PATCH/DELETE /api/v1/portal/flows` + nós
     (`POST /flows/{id}/nodes`, reorder `PUT /flows/{id}/nodes/order`).
2. **Redirecionadores** — **reuso da infra da Spec 109** (`traffic_links`):
   endpoints de listagem agrupada `GET /api/v1/portal/redirects` (filtro por
   `filtro de tráfego_enabled=true` ou tipo "redirect") + `POST /portal/redirects`
   (cria um `traffic_link` com filtro de tráfego). **Sem backend novo de tabela** — é
   uma visão/config da Spec 109.
3. **Remarketing** — `remarketing_campaigns` (V66): `id, merchant_id, name,
   message, send_after_minutes (ex.: 30min após abandono), status, created_at`
   + **job ShedLock `foxcash-remarketing`** (padrão Spec 53) que detecta
   cobrança **PENDING expirada** (Spec 23/28 — `expired`/vencida) com lead
   vinculado → publica a **recuperação** no outbox (Spec 35 — o bot envia a
   mensagem via API, padrão Spec 51). Log de disparos (`remarketing_logs`:
   `id, campaign_id, transaction_id, lead_id, status SENT/FAILED, created_at`).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Automações: Meus Fluxos (desenho
e controle dos funis), Redirecionadores (links inteligentes) e Remarketing
(recuperação de carrinhos). Lacuna: as mensagens/botões existem (Specs 50/51),
mas não há fluxos multi-nó, nem remarketing. Os redirecionadores reusam a Spec
109 (criada junto).

## Critérios de aceite

1. **Migration V66**: `bot_flows`, `bot_flow_nodes`, `remarketing_campaigns`,
   `remarketing_logs` — FKs, índices. **V66** (V65 = Spec 115).
2. **Fluxos**: CRUD + nós (validação por tipo — `content` obrigatório,
   `SEND_LINK` URL http(s) SSRF-safe via `WebhookUrlValidator`); reorder;
   ativar/desativar. O **storefront** (Spec 48) ou o bot expõem o fluxo?
   — decisão: `GET /api/v1/foxcash/flows/{id}` (API key) para o bot renderizar
   (padrão Spec 51).
3. **Redirecionadores**: `GET /portal/redirects` (lista de `traffic_links`
   com `filtro de tráfego_enabled` — reuso Spec 109) + `POST /portal/redirects` (cria
   com filtro de tráfego). Sem tabela nova.
4. **Remarketing**: CRUD campanhas (mensagem, `send_after_minutes` ≥ 5,
   status); job ShedLock (cron `foxcash.remarketing.cron`, kill-switch) que
   varre cobranças PENDING expiradas na janela `[send_after_minutes]` → para
   cada uma com lead (Spec 75) e sem log duplicado → publica `REMARKETING_SEND`
   no outbox (Spec 35) + registra `remarketing_logs` (idempotente por
   `transaction_id`).
5. **Sem PII em logs**; rate limit; contratos existentes inalterados.
6. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 50/51/49** — ações/mensagens (reuso dos tipos de nó).
- **Spec 52** — bot config-driven (renderiza o fluxo).
- **Spec 109** — redirecionadores (traffic_links).
- **Spec 35/53** — outbox + job ShedLock.
- **Spec 75** — lead vinculado à cobrança (remarketing).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — fluxos/remarketing
  usam **bots da frota do perfil** (`store_bots.profile_id`, Specs 122/139;
  `PUT /api/v1/portal/profile/{id}/bots` é a fonte da frota); o bot-set do
  perfil ativo escopa fluxos e mensagens (Spec 124).

## Fora de escopo

- **UI** — Spec 118.
- **Fluxo visual drag-and-drop complexo** — editor de nós simples (nós em
  sequência linear na v1; branching = evolução).
- **Remarketing multi-canal (SMS/e-mail)** — só mensagem via bot.
