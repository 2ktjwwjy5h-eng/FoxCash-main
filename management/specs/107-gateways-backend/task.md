---
name: Gateways — visão dos adquirentes, fallback e catálogo (backend)
status: audited
---

# Spec 107 — Task: Gateways (backend)

## F0 — Service + DTOs

- [x] **T0.1** — `PortalGatewayService`: `overview(merchantId)` (order da
      policy, circuitState, extrato por provider); `catalog()`.
- [x] **T0.2** — DTOs: `GatewayOverviewResponse`, `GatewayProvider`,
      `GatewayCatalogItem`.

## F1 — Controller

- [x] **T1.1** — `PortalGatewayController` (`/api/v1/portal/gateways`,
      `/api/v1/portal/gateways/catalog`), ROLE_MERCHANT_PORTAL.
- [x] **T1.2** — Spec 91 intacta (nenhuma mudança no roteamento).
- [x] **T1.3** — Visão por **perfil ativo** (Spec 139): resolver fallback/ordem
      via `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` —
      `BOT_ONLY` nunca override (rota por risco); catálogo global.

## F2 — Testes + fechamento

- [x] **T2.1** — `PortalGatewayServiceTest` (D4) + `PortalGatewayControllerTest`.
- [x] **T2.2** — `mvn test` verde.
- [x] **T2.3** — E2E `portal-gateways.spec.js` + roadmap/status.

## Verificação (`/spec-verify 107`)

- [x] Checar âncoras (`RoutingPolicyResolver`, `PixProviderHealthRegistry`,
      `RoutingProperties`, `PixTransactionJpaRepository`) — sem achados.

---

## Audit (Spec 107) — 2026-08-17

Implementação auditada com sucesso. Código revisado, testes validados.

**Backend (Spec 107) — entregue e auditado:**
- `GET /api/v1/portal/gateways` (ROLE_MERCHANT_PORTAL) → `{providers: [...]}`:
  displayName (pagarme→Pagar.me, asaas→Asaas, mock→Simulador, suitpay→Suitpay),
  order (1=principal, 2/3=fallback — da policy do lojista via
  `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` — Spec 139),
  circuitState (do `PixProviderHealthRegistry`, ausente → CLOSED), volumePeriod +
  countPeriod (Σ/COUNT APPROVED por provider no mês corrente — reuso Spec 105).
  Sem providers → lista vazia (sem erro). Providers fora da policy não listados (KISS).
- `GET /api/v1/portal/gateways/catalog` → `[{id, name, category, available,
  configured}]` — catálogo estático (PIX: pagarme/asaas/mock available; CARTÃO:
  cielo; CRIPTO: bitpay; available:false) + `configured` (presente no
  `ProviderCatalog.availableRoutes()`, sem PADRAO).
- Sem token/segredo em resposta (DTOs sem credenciais); sem PII em logs (nenhum
  log no service); rate limit por principal `/api/v1/**` (Spec 58) cobre a rota;
  Spec 91 intacta (nenhuma mudança no roteamento).
- Escopo por perfil (Spec 139): header `X-Profile-ID` resolvido via
  `ProfileScopeResolver.resolveActiveProfileId` (ausente/inválido → empty →
  rota por risco); perfil BOT_ONLY nunca aplica override.

**Verificações executadas:**
- `mvn test` (backend): BUILD SUCCESS — 2082 testes, 0 falhas, 0 erros
  (inclui `PortalGatewayServiceTest` 4/4 + `PortalGatewayControllerTest` 3/3).
- E2E `portal-gateways-ui.spec.js` (Spec 107/108): 2/2 passed.
- App subida com envs do CI (routing mock,suitpay) — fila Simulador → Suitpay.

**Achados:**
- 0 achados de código.
- 1 achado de doc corrigido: design D4 citava `portal-gateways.spec.js`; o
  arquivo real é `portal-gateways-ui.spec.js` (cobre os endpoints da 107 também).
