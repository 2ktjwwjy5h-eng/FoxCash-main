---
name: Painel de usuários — backend escrita (configuração fina por lojista + controle de conta)
status: audited
---

# Spec 98 — Task: Painel de usuários (backend de escrita)

## F0 — Migration + enum + domínio

- [x] **T0.1** — Migration **`V61__merchant_config.sql`** (aditiva, padrão D2):
      11 colunas em `merchants` (`pix_fee_type`, `pix_fee_fixed`,
      `pix_fee_percent`, `pix_fee_threshold`, `withdrawal_fee`, `pix_min`,
      `pix_max`, `withdrawal_min`, `withdrawal_max`,
      `rate_limit_rps`, `rate_limit_rpm`, `api_blocked`, `honeypot`).
      `MerchantStatus` += `BANNED` (sem migration de constraint — VARCHAR(20)
      sem CHECK).
      **Coordenação (Spec 122, revisado 2026-08-15):** `gateway_route` **NÃO é
      criada no `merchants`** — a rota agora vive em **`merchant_profiles`**
      (entidade da Spec 122, migration V69; dona: Spec 122). O master console
      desta spec não gerencia rota de gateway.
- [x] **T0.2** — `Merchant` (domínio): `updateConfig(...)` com validações
      (negativos → exceção; `pix_fee_type` inválido; percentual 0–100;
      `pix_fee_threshold > 0`; `withdrawal_fee ≥ 0`; `min ≤ max`; `rate_limit_*
      > 0` — **sem `gateway_route`**, rota é por perfil na Spec 122);
      `deactivate()`, `activate()`,
      `ban(reason)` (irreversível), `debit(amount, reason)` (negativo
      permitido), `markApiBlocked(boolean)`, `markHoneypot(boolean)`.
- [x] **T0.3** — `MerchantJpaEntity` + `MerchantMapper`: campos novos.

## F1 — Porta + adapter de config

- [x] **T1.1** — Porta out `MerchantConfigProvider` (lê overrides):
      `config(merchantId)`, `rateLimit(merchantId)`, `pixFee(merchantId)`
      (**sem `gatewayRoute`** — rota por perfil na Spec 122); e
      `MerchantConfigStore`
      (grava): `save(merchant)`.
- [x] **T1.2** — `MerchantConfigPersistenceAdapter` (infrastructure): leituras
      na entity (reuso do `findByIdForUpdate` para escrita — SEC-19) e
      `save`. **Cache curto-TTL do config** (padrão Spec 65 Fase 2 — mas com
      invalidação na escrita; decisão: sem cache na v1, os pontos de runtime
      leem a entity com o `AuthenticatedMerchantResolver` já resolvido — ver
      T3.3; medir se precisar).
- [x] **T1.3** — Ledgers: `WalletLedgerEntryType` += `ADMIN_DEBIT`;
      `MasterLedgerEntryType` += `ADMIN_DEBIT` (enums Java; entry_type
      VARCHAR(30) — Spec 52).

## F2 — Service (RBAC + auditoria + step-up)

- [x] **T2.1** — `MerchantConfigService`:
      `getConfig(merchantId)` (404), `updateConfig(merchantId, request)`
      (parcial, auditoria `MERCHANT_CONFIG_UPDATED` na mesma transação),
      `debit(merchantId, amount, reason)` (step-up + ledger duplo + auditoria
      `ADMIN_DEBIT` com before/after saldo), `markHoneypot(merchantId, enabled)`
      (auditoria `HONEYPOT_MARKED/UNMARKED`), `deactivate/activate`
      (auditoria `MERCHANT_DEACTIVATED/ACTIVATED`), `ban(merchantId, reason)`
      (step-up + auditoria `MERCHANT_BANNED`).
- [x] **T2.2** — Permissões: `MasterPermission` += `merchant.config`,
      `merchant.debit`, `merchant.ban` (catálogo 9 → 12); guard tier-based
      (OPERATOR) já concede por `values()`; `merchant.debit`/`merchant.ban`
      na lista `foxcash.security.master.step-up.required-permissions`
      (default) — verificar `MasterStepUpProperties`/yml.
- [x] **T2.3** — `MasterAuditAction` += `MERCHANT_CONFIG_UPDATED`, `ADMIN_DEBIT`,
      `HONEYPOT_MARKED`, `HONEYPOT_UNMARKED`, `MERCHANT_BANNED`,
      `MERCHANT_ACTIVATED`, `MERCHANT_DEACTIVATED`.

## F3 — Runtime (pontos de integração)

- [x] **T3.1** — `PricingCalculator`: aceita `merchantId`+`amount` e aplica a
      regra FIXED/PERCENT/threshold (D4.1); call-sites (fatura Spec 8,
      `MerchantSalesService` Spec 25, `MerchantPanelQueryService` Spec 97)
      atualizados; fallback global.
- [x] **T3.2** — `PixProviderRouter` (Spec 91): **honeypot** precedência
      (D4.5) — trap antes de qualquer policy/perfil (override de rota removido
      desta spec; rota por perfil = Spec 122); `HoneypotPixProviderClient`
      (mock trap — create devolve QR fictício `HP<uuid>`, nunca webhook PAID).
- [x] **T3.3** — `AuthenticatedRateLimitFilter` (Spec 58): override rps/rpm
      por lojista (D4.3), fail-closed (ausente → global). Reuso do
      `AuthenticatedMerchantResolver` (Spec 65) — sem query extra.
- [x] **T3.4** — `ApiKeyAuthFilter` (Spec 12/24): `api_blocked` → 403
      `api_blocked` antes dos escopos.
- [x] **T3.5** — Honeypot runtime: gate de saque (Spec 14/32) consulta
      `honeypot` → 409 `honeypot_blocked` (vendedor e administrativo);
      `HoneypotAlertPublisher` (throttle por janela — padrão Spec 64)
      publica `HONEYPOT_TRIGGERED` no outbox (Spec 35, REQUIRES_NEW,
      fail-safe); `PlatformAction` += `HONEYPOT_TRIGGERED`.
- [x] **T3.6** — Status `BANNED` nos gates existentes: `ApiKeyAuthFilter`
      (só ACTIVE), `MerchantPortalAuthService` (`merchant_not_active`),
      `Merchant.withdraw`/cobranças (Spec 12/14) — BANNED bloqueia tudo.

## F4 — Controller REST + handler

- [x] **T4.1** — `MasterConfigController` (`@RequestMapping("/api/v1/master")`,
      ROLE_MASTER): `GET/PUT /merchants/{id}/config`,
      `POST .../config/debit`, `POST .../config/honeypot`,
      `POST .../config/deactivate`, `POST .../config/activate`,
      `POST /merchants/{id}/ban`. DTOs (`MerchantConfigResponse`/
      `MerchantConfigRequest` parcial/`DebitRequest`/`BanRequest`/
      `HoneypotRequest`).
- [x] **T4.2** — `GlobalExceptionHandler`: 400 `invalid_argument`,
      `reason_required`, 404 `merchant_not_found`, 409 `invalid_state`/
      `honeypot_blocked`, 403 `forbidden`/`step_up_required` (reuso).
- [x] **T4.3** — `RateLimitKey`/cardinalidade: rotas `/master/**` já cobertas
      (Spec 69); `step_up` já tem rate limit (Spec 69).

## F5 — Testes unitários/integração

- [x] **T5.1** — `MerchantConfigServiceTest` (validações, PATCH parcial,
      activate/deactivate, ban irreversível, honeypot mark/unmark, debit com
      ledger + saldo negativo, RBAC 403, step-up 403).
- [x] **T5.2** — `MerchantTest` (novos métodos), `PricingCalculatorTest`
      (FIXED/PERCENT/threshold/fallback), `PixProviderRouterTest` (override +
      honeypot), `AuthenticatedRateLimitFilterTest` (override + fail-closed),
      `ApiKeyAuthFilterTest` (api_blocked), `HoneypotAlertPublisherTest`
      (throttle), `MasterConfigControllerTest`, `MasterAuditRecorderTest`.
- [x] **T5.3** — `mvn test` verde (suíte completa).

## F6 — E2E e fechamento

- [x] **T6.1** — E2E `master-config.spec.js` (D7): config taxa FIXED/PERCENT +
      gateway override + rate limit por lojista + deactivate/activate +
      honeypot on/off (trap, sem PAID, saque 409, alerta throttled) +
      api_blocked 403 + debit com step-up (ledger + auditoria) + ban
      (tudo bloqueado + auditoria).
- [x] **T6.2** — `global-setup.js` (L6): truncar/limpar se necessário (só
      colunas — sem tabela nova; BANNED no seed limpo no `beforeAll`).
- [x] **T6.3** — README: endpoints de config/controle (documentados como
      rotas master).
- [x] **T6.4** — Atualizar roadmap/status.

## Verificação (`/spec-verify 98`)

- [x] Checar critérios ↔ design ↔ task contra o código real (migration V61
      livre, `MerchantStatus`, `PricingCalculator` call-sites,
      `PixProviderRouter`, `AuthenticatedRateLimitFilter`,
      `ApiKeyAuthFilter`, `MasterPermission` 9→12, `MasterStepUpProperties`,
      `MasterAuditAction`, `WalletLedgerEntryType`/`MasterLedgerEntryType`) —
      sem achados de escopo.

## `/spec-audit 98` (2026-08-17) — AUDITADA

**13/13 critérios OK, 1 achado de DOC corrigido, 0 abertos.** Verificação:
`MasterConfigController` com os 7 endpoints (config GET/PUT parcial, debit com
step-up + ledger duplo `ADMIN_DEBIT`, honeypot, activate/deactivate, ban
BANNED irreversível), `MerchantConfigService`, regra de taxa FIXED/PERCENT/
threshold no `PricingCalculator`, rate limit por lojista (fail-closed), api_blocked
403, RBAC + step-up (Spec 69), auditoria (Spec 37). **Achado corrigido: task.md
estava `verified` com 0/26 checkboxes** (a implementação existia mas o checklist
nunca foi atualizado) — 26/26 marcados + status → `audited`. **Desvio
documentado (por design):** seção gateway do config NÃO existe — a rota foi
movida para por-perfil (Spec 122, coordenação 2026-08-15), sem endpoint de rota
no master console. Testes: `MerchantConfigServiceTest` 14/14 (0 falhas) + E2E
`master-config.spec.js` e `master-config-ui.spec.js` presentes. Frontmatter/
roadmap → `audited`.
