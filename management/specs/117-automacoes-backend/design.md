---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (backend)
status: verified
---

# Spec 117 — Design: Automações avançadas (backend)

## D1 — Conceito

Três serviços: `FlowService` (funis multi-nó), `RedirectService` (visão da Spec
109) e `RemarketingService` (job). Todos escopados por lojista.

```text
PortalFlowController (ROLE_MERCHANT_PORTAL) — flows + nós
PortalRedirectController (ROLE_MERCHANT_PORTAL) — visão/config da Spec 109
PortalRemarketingController (ROLE_MERCHANT_PORTAL) — campanhas
FoxFlowController (API key) — GET /nyx/flows/{id} (bot renderiza)
RemarketingScheduler (ShedLock + JobExecutionService)
```

## D2 — Migration V66

```sql
CREATE TABLE bot_flows (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  name VARCHAR(120) NOT NULL, trigger VARCHAR(16) NOT NULL DEFAULT 'MESSAGE', -- MESSAGE|BUTTON
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE bot_flow_nodes (id UUID PK, flow_id UUID NOT NULL REFERENCES bot_flows(id) ON DELETE CASCADE,
  type VARCHAR(32) NOT NULL, -- MESSAGE|SEND_LINK|ADD_TO_GROUP|GRANT_ROLE (Spec 49)
  content VARCHAR(1000) NOT NULL, sort INT NOT NULL DEFAULT 0);
CREATE TABLE remarketing_campaigns (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  name VARCHAR(120) NOT NULL, message VARCHAR(1000) NOT NULL,
  send_after_minutes INT NOT NULL CHECK (send_after_minutes >= 5),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL);
CREATE TABLE remarketing_logs (id UUID PK, campaign_id UUID NOT NULL REFERENCES remarketing_campaigns(id),
  transaction_id UUID NOT NULL REFERENCES pix_transactions(id) UNIQUE,
  lead_id UUID NULL, status VARCHAR(16) NOT NULL DEFAULT 'SENT', created_at TIMESTAMPTZ NOT NULL);
```

**V66** (V65 = Spec 115).

## D3 — Fluxos

- CRUD + nós (validação por tipo; `SEND_LINK` SSRF-safe); reorder `PUT
  /flows/{id}/nodes/order {ids[]}` (padrão Spec 50).
- `GET /api/v1/foxcash/flows/{id}` (API key — ROLE_MERCHANT): `{flow, nodes[]}`
  para o bot renderizar (padrão Spec 51/scheduled-messages).

## D4 — Redirecionadores

- `GET /api/v1/portal/redirects` → lista de `traffic_links` (Spec 109) com
  `filtro de tráfego_enabled=true`; `POST /api/v1/portal/redirects` → cria um
  `traffic_link` com filtro de tráfego (mesmo service da Spec 109). Sem tabela nova.

## D5 — Remarketing

- CRUD campanhas (mensagem, `send_after_minutes >= 5`).
- `RemarketingScheduler` (ShedLock + `JobExecutionService`, cron
  `foxcash.remarketing.cron`, kill-switch): varre `pix_transactions` PENDING
  expiradas (status `EXPIRED`/vencida da Spec 23/28) criadas há
  `>= send_after_minutes`, com `bot_id`/lead (Spec 75) e **sem log duplicado**
  (UNIQUE `transaction_id`) → publica `REMARKETING_SEND` no outbox (Spec 35,
  `REQUIRES_NEW`, fail-safe) + registra `remarketing_logs` SENT.
- Métricas `nyx_remarketing_*`.

## D6 — Auditoria

`PlatformAction` += `FLOW_CREATED/...`, `REMARKETING_CREATED/...`.

## D7 — Testes

**Unitários:** `FlowServiceTest` (CRUD, nós, validação, reorder, 404
cross-lojista), `RemarketingServiceTest` (campanha, detecção de expirada,
idempotência do log, `send_after`), `RemarketingSchedulerTest`. **Integração:**
controllers. **E2E** (`portal-automacoes.spec.js`): criar fluxo + nós →
`GET /nyx/flows/{id}`; criar redirect (Spec 109); campanha de remarketing +
job manual → log + outbox.

## D8 — Não ignorar a Spec 139 (frota por perfil)

Fluxos (`bot_flows`) e remarketing usam **bots da frota do perfil**:
`store_bots.profile_id` (Specs 122/139 — `PUT /api/v1/portal/profile/{id}/bots`
é a fonte da frota). As listas/consultas devem escopar pelo bot-set do perfil
ativo (Spec 124) e pelos endpoints do bot (`/nyx/flows/{id}`) sob o header
`X-Profile-ID` quando o lojista estiver autenticado no portal.
