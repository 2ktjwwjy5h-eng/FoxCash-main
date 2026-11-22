---
name: Shell do portal — header global (backend)
status: audited
references:
  - "management/vision.md#V77"
  - "management/specs/21-merchant-dashboard/requirements.md"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 101 — Shell do portal: header global (backend)

## O que entrega

Backend **aditivo read-only** que alimenta o **header global** do portal do
lojista (Spec 102): saudação com hora e data, **faturamento do período com
meta e barra de progresso**, **leads recentes** (carrossel de avatares) e
**notificações** (contador + lista leve). Nenhuma mudança nos contratos
existentes.

1. **`GET /api/v1/portal/shell`** — agregação única para o header:
   - `greeting`: saudação por hora do dia (`Boa madrugada/`Boa dia/`Boa tarde/`
     `Boa noite`) + `name` do lojista + `date` (data corrente, fuso do cliente
     via offset no header ou UTC — decisão na implementação).
   - `billing`: `{periodStart, periodEnd, billedAmount, goalAmount,
     progressPercent}` — faturamento acumulado do período (mês corrente UTC,
     mesmo padrão do `dashboard/summary` Spec 21) + meta do lojista
     (`foxcash.portal.journey-goal-amount` — reuso Spec 21).
   - `recentLeads`: últimos N leads do lojista (nome/iniciais — do CRM Spec 83),
     para o carrossel de avatares.
   - `notifications`: contador + últimas notificações (reuso do que existir —
     o portal não tem feed de notificações; fonte inicial: eventos recentes do
     `platform_activity_log` do lojista (Spec 41) ou outbox `SENT` (Spec 35) —
     decisão na implementação; contrato estável).
2. **`GET /api/v1/portal/shell/recent-leads`** — carrossel: `{id, name,
   initials, lastInteractionAt}` — reuso `bot_leads` (Spec 83/75); sem PII em
   logs (nome mascarado no `SensitiveDataLoggingFilter`).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — barra de topo geral do sistema
de referência: indicador de faturamento em tempo real com barra de meta,
saudação/data, carrossel de leads e atalhos. Lacuna: o portal não tem header
global — o `Layout.tsx` atual só tem o menu lateral e o nome do lojista no
rodapé. Backend: os dados existem (`dashboard/summary` Spec 21 para o billing;
`bot_leads` Specs 75/83 para leads; `platform_activity_log` Spec 41 para
notificações) — falta o endpoint agregado.

## Critérios de aceite

1. **`GET /api/v1/portal/shell`** (ROLE_MERCHANT_PORTAL) → `{greeting, date,
   billing, recentLeads, notifications}`. Campos: `greeting.name` do lojista;
   `date` = data corrente formatada (pt-BR) no fuso do request (offset) ou UTC
   (decisão na implementação, documentada); `billing` com `billedAmount`,
   `goalAmount`, `progressPercent` (cap 100 — reuso Spec 21); `recentLeads`
   com até 8 itens `{id, name, initials, lastInteractionAt}`; `notifications`
   com `{unreadCount, items[]}` (até 10).
2. **`GET /api/v1/portal/shell/recent-leads`** → lista de leads recentes
   (ordenada por `lastInteractionAt` desc, cap 8), com `initials` derivadas do
   nome (2 letras). Sem lead → `[]` (empty).
3. **Escopo por lojista**: sempre `merchantId` do principal (nunca do body) —
   padrão Spec 20/IDOR-safe.
4. **Sem PII em logs**: nome do lead mascarado nos logs (padrão
   `SensitiveDataLoggingFilter` — Spec 20/B7).
5. **Segurança/rate limit**: rotas `/api/v1/portal/**` já protegidas
   (ROLE_MERCHANT_PORTAL) + cardinalidade no `RateLimitKey` (Spec 58/SEC-23 —
   `/api/v1/portal` já coberto).
6. **Contratos existentes inalterados**: `/portal/me`, `/portal/dashboard/*`,
   `/portal/crm/*` — nenhuma mudança.
7. **Testes**: unitários + integração (shell com dados, progress cap 100,
   recent-leads ordenado/empty, 401/403) e E2E Playwright (login → shell →
   header renderizado); `mvn test` verde + E2E completo sem regressão.

## Dependências

- **Spec 21** (dashboard) — billing do período + `journey-goal-amount`.
- **Spec 83/75** (CRM/leads) — `bot_leads` para o carrossel.
- **Spec 41** (atividade) — `platform_activity_log` como fonte das notificações
  (ou Spec 35 outbox — decisão na implementação).
- **Spec 46/73** (portal/UX) — consumido pela Spec 102.
- **Spec 139** (escopo do perfil + lente, `in-progress` 2026-08-16) — o header
  global é **escopado pelo perfil ativo** (Spec 124: faturamento/leads por
  `profile_id`); o shell deve respeitar o `X-Profile-ID` e a lente Bot/Gateway
  (`foxcash.portal.lens`) ao agregar os dados — um perfil `GATEWAY_ONLY` não
  deve somar faturamento de bots.

## Fora de escopo

- **UI do header** — Spec 102.
- **Feed de notificações completo** (marcar lida, histórico paginado) —
  evolução; aqui só contador + últimas.
- **Configuração da meta pelo lojista** — a meta vem da config global (Spec 21);
  edição da meta é evolução.
