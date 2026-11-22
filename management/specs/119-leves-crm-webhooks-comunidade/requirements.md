---
name: Leves — CRM enriquecido, Webhooks melhorado e Comunidade
status: implemented
references:
  - "management/vision.md#V86"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/84-crm-painel/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 119 — Leves: CRM enriquecido, Webhooks melhorado e Comunidade (backend + UI)

## O que entrega

Fechamento do bloco de melhorias leves do portal (descrição de produto do
usuário 2026-08-15), **backend + UI na mesma spec** (são pequenos e coesos).

1. **CRM enriquecido** (sobre o `CrmPage` existente — Specs 83/84):
   - **Colunas novas** na tabela de clientes: **Plano** (produto/plano da última
     compra — `pix_transactions.plan_id`/`bot_leads`), **Fluxo** (fluxo/funil de
     origem), **Data da última interação** (`bot_leads.updated_at` — já existe).
   - **Busca por telefone** (além de nome/@username/e-mail/ID — hoje não busca
     telefone).
   - Backend: `GET /api/v1/portal/crm/leads` ganha filtro `q` estendido
     (telefone) + campos `lastPlanName`/`lastInteractionAt`/`flowName` no DTO
     (aditivo).
2. **Webhooks melhorado** (sobre o `WebhooksPage` — Spec 35):
   - **Validador de payload JSON**: gaveta/drawer interativa com o **JSON real**
     do evento (contrato do outbox Spec 35 — sem PII, payload mascarado).
   - **Aviso de segurança**: HMAC-SHA256, HTTPS obrigatório, **desativação
     automática após 5 falhas** (dados reais da Spec 35 — `attempts`/`status`).
   - Backend: `GET /api/v1/portal/webhooks/{id}/sample?type=` (exemplo do
     payload — mock do contrato; sem PII) + `GET /portal/webhooks` devolve
     `status` (ACTIVE/INACTIVE) e `lastError`/`attempts` (aditivo).
3. **Comunidade** (novo):
   - `community_posts` (V67): `id, title, content, published_by (master id),
     published_at, pinned` — avisos/novidades da plataforma.
   - `GET /api/v1/portal/community` (lista read-only, paginada) + seed/CRUD no
     **Master Console** (`GET/POST/PATCH /api/v1/master/community/posts` —
     ROLE_MASTER, RBAC Spec 69, auditoria Spec 37).
   - UI portal: página `/community` (lista de posts + detail) — KISS (sem
     chat/comentários).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Base de Clientes (CRM com
plano/fluxo/data última interação e busca por telefone), Webhooks (validador de
payload + aviso de segurança) e Comunidade (canal de avisos/suporte). Reuso
máximo do que já é `audited` (Specs 83/84/35).

## Critérios de aceite

1. **CRM**: `GET /portal/crm/leads` — filtro `q` inclui telefone; DTO aditivo
   `lastPlanName`, `lastInteractionAt` (de `bot_leads.updated_at`),
   `flowName` (funil de origem — `bot_funnel_events`/nó do fluxo Spec 117, ou
   `null`). A tabela da UI ganha as colunas (Spec 84).
2. **Webhooks**: `GET /portal/webhooks` devolve `status` + `lastError` +
   `attempts` (aditivo); `GET /portal/webhooks/{id}/sample?type=` → exemplo
   JSON do payload do evento (mock do contrato Spec 35 — **sem PII**); a UI
   ganha o drawer de payload + aviso de segurança (HMAC/HTTPS/5 falhas).
3. **Comunidade**: migration **V67** `community_posts` (V66 = Spec 117);
   `GET /api/v1/portal/community` (ROLE_MERCHANT_PORTAL, paginada, cap 50);
   `GET/POST/PATCH/DELETE /api/v1/master/community/posts` (ROLE_MASTER, RBAC +
   auditoria `COMMUNITY_POST_*`); seed opcional de post inicial.
4. **Sem PII** no sample de webhook (payload mascarado — padrão Spec 35/64);
   rate limit; contratos existentes inalterados.
5. **Testes**: `mvn test` verde + E2E (`portal-leves.spec.js`): CRM com coluna
   plano/telefone; webhook com sample + aviso; comunidade com post seed;
   `npm run build` limpo no portal.

## Dependências

- **Spec 83/84** (CRM) — leads, Live Chat.
- **Spec 35** (webhooks) — outbox, contrato, status.
- **Spec 41/37** (auditoria) — posts da comunidade (master).
- **Spec 73** (UX).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — CRM: leads → bot →
  **frota do perfil ativo** (Spec 124, `store_bots.profile_id`); as colunas
  novas e a busca por telefone respeitam o bot-set do perfil. Webhooks sem
  mudança.

## Fora de escopo

- **Chat/comentários na comunidade** — só posts read + seed (KISS).
- **CRM com edição de leads** — só leitura enriquecida.
- **Teste real de entrega de webhook** — o `POST /test` da Spec 35 já existe;
  aqui só o sample do payload.
