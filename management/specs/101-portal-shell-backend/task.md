---
name: Shell do portal — header global (backend)
status: audited
---

# Spec 101 — Task: Shell do portal (backend)

## F0 — Service + controller

- [x] **T0.1** — `PortalShellService` (aplicação): `shell(merchantId, zoneOffset)`
      → `PortalShellResponse` (greeting por hora, billing do mês via
      `MerchantDashboardUseCase`, recent-leads via `BotLeadRepository`,
      notificações via `PlatformActivityRepository`/outbox); `recentLeads(...)`.
- [x] **T0.2** — `PortalShellController` (`/api/v1/portal/shell` +
      `/api/v1/portal/shell/recent-leads`), ROLE_MERCHANT_PORTAL, sempre do
      principal. Header `X-Timezone-Offset` opcional (fallback UTC).
- [x] **T0.3** — DTOs: `PortalShellResponse`, `ShellGreeting`, `ShellBilling`,
      `ShellLead`, `ShellNotifications` — records.
- [x] **T0.4** — `initials()` helper (2 letras do nome — mesma lógica do CRM).

## F1 — Testes

- [x] **T1.1** — `PortalShellServiceTest`: saudação por hora (4 faixas),
      progress cap 100, billing vazio → 0/0/0, recent-leads ordenado, sem lead
      → `[]`, notificações vazias.
- [x] **T1.2** — `PortalShellControllerTest`: 200 shape, 401 sem token, 403 com
      API key, 400 em offset inválido.
- [x] **T1.3** — `mvn test` verde (suíte completa, sem regressão).

## F2 — E2E + fechamento

- [x] **T2.1** — E2E `portal-shell.spec.js`: login → `GET /portal/shell` →
      campos corretos (saudação/data/faturamento/leads) → 401/403.
- [x] **T2.2** — Atualizar roadmap/status.

## Verificação (`/spec-verify 101`)

- [x] Checar critérios ↔ design ↔ task contra o código real
      (`MerchantDashboardUseCase`, `BotLeadRepository`, `PlatformActivityRepository`,
      `QueryParamParser`, `RateLimitKey` portal) — sem achados de escopo.

## Implementação (2026-08-17)

- F0–F2 concluídas: `PortalShellService` (greeting por hora com fuso de exibição,
  billing via `MerchantDashboardUseCase` + `PortalProperties.journey-goal-amount`,
  recent-leads via `BotLeadRepository.findRecentByMerchantId` — novo método na
  porta + JPQL no `BotLeadJpaRepository` com sort `COALESCE(updated_at, created_at)`,
  notificações via `PlatformActivityRepository.query` MERCHANT), `PortalShellController`
  (`GET /api/v1/portal/shell` + `GET /api/v1/portal/shell/recent-leads`,
  `X-Timezone-Offset` em minutos, fallback UTC), DTOs records.
- **Desvios registrados**: (1) `unreadCount` = nº de itens retornados (o design
  dizia 0 — sem estado de "lida" nesta versão; todas as recentes contam, o badge
  da UI funciona); (2) fuso aplicado via `Instant.now(clock).atOffset(offset)`
  (converte o instante — `LocalDateTime.atOffset` apenas anexa o offset);
  (3) `initials` de nome único = 2 primeiras letras ("Maria" → "MA").
- `mvn test` **2082/0/0** (+14 novos: service 9 + controller 5) — zero regressão.

---

## Audit (Spec 101) — 2026-08-17

Implementação auditada com sucesso. Código revisado, testes validados.

**Backend (Spec 101) — entregue e auditado:**
- `GET /api/v1/portal/shell` retorna o shell do portal: saudação (com hora do dia + nome do lojista), data, faturamento do mês, meta do mês, avatares (carrossel de leads recentes com iniciais) e badge de notificações (nº de eventos recentes do lojista).
- Auditoria `SensitiveDataLoggingFilter` confirma: nenhuma PII (nome/e-mail do lead) é logada pelo endpoint.
- Persistência: JPQL com escopo por merchant + COALESCE na ordenação; adapter com `List` limitado ao cap (sem `findAll` cru).
- Resposta formatada conforme design D2.1: `greeting.date` dentro do objeto `greeting`.

**Verificações executadas:**
- `mvn test` (backend): BUILD SUCCESS — 2082 testes, 0 falhas, 0 erros.
- App subida com `FOXCASH_PORTAL_SERVE_FRONTEND=true` + demais envs do CI.
- E2E `portal-shell-ui.spec.js` (Specs 101/102): 2/2 passed.
- `curl /api/v1/portal/shell` sem token → 401 (coberto também no E2E).
