---
name: Shell do portal — header global (backend)
status: audited
---

# Spec 101 — Design: Shell do portal (backend)

## D1 — Conceito

Um **`PortalShellService`** (aplicação) que **compõe dados já existentes** em
uma resposta única para o header, sem tabela nova:

```text
PortalShellController (REST, ROLE_MERCHANT_PORTAL)
   └─ PortalShellService (aplicacao)
        ├─ MerchantDashboardUseCase (Spec 21) — billing do período + meta
        ├─ LeadRepository (Spec 75/83) — recent-leads (findByMerchantId ... orderBy)
        └─ ActivityLog/Outbox (Spec 41/35) — notificações (contador + últimas)
```

## D2 — Endpoints

### D2.1 `GET /api/v1/portal/shell`

Resposta (record `PortalShellResponse`):

```json
{
  "greeting": { "salutation": "Boa madrugada", "name": "Gansa",
                "date": "15/08/2026", "time": "02:30" },
  "billing": { "periodStart": "2026-08-01T00:00:00Z", "periodEnd": "2026-08-31T23:59:59Z",
               "billedAmount": 293.58, "goalAmount": 1000.00, "progressPercent": 29.36 },
  "recentLeads": [ { "id": "uuid", "name": "Joao S.", "initials": "JS",
                     "lastInteractionAt": "2026-08-15T02:00:00Z" } ],
  "notifications": { "unreadCount": 2, "items": [ { "id": "uuid", "type": "SALE_PAID",
                     "message": "Venda aprovada: R$ 4,89", "createdAt": "..." } ] }
}
```

- **greeting**: salutation por `LocalTime` (0–5 madrugada, 6–11 bom dia,
  12–17 boa tarde, 18–23 boa noite); `date`/`time` no fuso do request via
  header `X-Timezone-Offset` (minutos) — fallback UTC. O fuso é usado **só
  para exibição** (nunca para cálculos de negócio — padronizar UTC internamente).
- **billing**: `billedAmount` = Σ APPROVED do mês corrente (reuso do
  `MerchantDashboardUseCase.summary` ou query própria); `goalAmount` =
  `foxcash.portal.journey-goal-amount` (Spec 21); `progressPercent` =
  `min(100, billed/goal*100)`.
- **recentLeads**: `bot_leads` do lojista ordenado por `updated_at`/última
  interação desc, cap 8 (Spec 83 — `BotLeadJpaRepository`). `initials` =
  primeiras letras do nome (helper — mesma lógica do CRM Spec 84).
- **notifications**: fonte = eventos do `platform_activity_log` do lojista
  (Spec 41, actor MERCHANT, últimos 10, `unreadCount` = 0 por enquanto — sem
  estado de "lida" nesta versão; ou `notification_outbox` `SENT` do lojista
  (Spec 35) — decisão na implementação, contrato estável com `unreadCount`).

### D2.2 `GET /api/v1/portal/shell/recent-leads`

Lista crua do D2.1 `recentLeads` (para o carrossel recarregar sem o billing).

## D3 — Persistência

**Nenhuma migration** — reuso de `bot_leads`, `platform_activity_log`,
`notification_outbox`. A meta é config (`journey-goal-amount`).

## D4 — Configuração

Reuso `foxcash.portal.journey-goal-amount` (Spec 21). Sem properties novas.

## D5 — Testes

**Unitários:** `PortalShellServiceTest` (saudação por hora, progress cap 100,
billing vazio → 0, recent-leads ordenado, notificações vazias → `[]`).
**Integração:** `PortalShellControllerTest` (200 shape, 401 sem token, 403 com
API key). **E2E** (`portal-shell.spec.js`): login → `GET /portal/shell` →
header renderizado na UI (Spec 102) com saudação/data/faturamento.
