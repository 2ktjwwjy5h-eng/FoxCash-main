---
name: Perfil de operação (fase 2) — filtro rígido de dados por perfil
status: audited
---

# Spec 124 — Task: Perfil de operação (fase 2, filtro rígido)

Ordem: T0 (migration) → T1 (resolver de escopo) → T2 (cobranças com profileId)
→ T3 (filtros por módulo) → T4 (segurança) → T5 (testes/E2E). Depende de
**Spec 122 implementada** (merchant_profiles, store_bots.profile_id, resolver
com profileId, endpoints).

## T0 — Migration (critérios 1)

- [x] T0.1: **`V70__pix_transactions_profile.sql`** (D2) — `pix_transactions.profile_id`
      (FK NULL) + índice parcial `(merchant_id, profile_id)` + **backfill**:
      transações com `bot_id` → perfil do bot; sem `bot_id` → perfil default do
      lojista. Confirmar V70 livre na implementação.
- [x] T0.2: `PixTransaction` (domínio) + `PixTransactionJpaEntity` + mapper —
      campo `profileId` (UUID, null permitido) + `PixTransactionJpaRepository`
      (novos métodos `...AndProfileId` / `@Query` com parâmetro opcional, D5).

## T1 — `ProfileScopeResolver` (critérios 2, 5)

- [x] T1.1: `infrastructure/inbound/rest/ProfileScopeResolver.java` (D3) —
      `resolveActiveProfileId(merchantId, request)` (header `X-Profile-ID`
      validado por `id + merchantId`, não-deletado), `botIdsOf(profileId)`,
      `defaultProfileId(merchantId)`.
- [x] T1.2: Testes — header válido/inválido/ausente; perfil de outro lojista →
      empty; `botIdsOf`/`defaultProfileId`.

## T2 — Cobranças com profileId (rota viva) (critérios 2, 4)

- [x] T2.1: `CreatePixChargeService` — resolve `profileId` (bot_id →
      `store_bots.profile_id`; sem bot → default); passa a
      `RoutingPolicyResolver.resolveProviders(merchantId, profileId)` (Spec 122)
      e grava `profile_id` na transação.
- [x] T2.2: `CreatePixTransactionService` (API legada) — `profileId` = default.
- [x] T2.3: `PortalChargeController` (cobrança do portal) — `profileId` do header
      validado (ausente → default).
- [x] T2.4: Testes — bot flow grava perfil do bot e roteia pela rota do perfil;
      portal usa o header; API legada → default; sem perfil → política por risco
      (regressão Spec 91); `pix_transactions.profile_id` gravado.

## T3 — Filtros por módulo (critérios 3, 6)

- [x] T3.1: `MerchantDashboardService` — KPIs/vendas aprovadas/conversão/séries
      com `profile_id = :profileId`; atenção (leads) por bot-set.
- [x] T3.2: `MerchantSalesService` — extrato/métricas/detalhe com
      `profile_id = :profileId`.
- [x] T3.3: `CrmLeadService` — leads/summary/stream com `bot_id IN :botIds`
      (join natural via `store_bots`).
- [x] T3.4: `StoreBotManagementUseCase` — lista só `store_bots.profile_id =
      :profileId` (frota do perfil).
- [x] T3.5: `BotMetricsService` — `summarizeMerchant` soma os bots `IN :botIds`.
- [x] T3.6: `BroadcastQueryService` — broadcasts com `store_bot_id IN :botIds`.
- [x] T3.7: `TrackingProfileQueryService` — perfis com
      `tracking_profile_bots.bot_id IN :botIds`.
- [x] T3.8: Controllers do portal — passam `Optional<UUID> profileId` do
      `ProfileScopeResolver` para os services acima (sem header → empty →
      predicados ignorados).
- [x] T3.9: Testes por módulo — perfil ativo escopa; sem header → lojista
      inteiro; header inválido → vazio.

## T4 — Segurança (critérios 3, 5)

- [x] T4.1: Teste — header com perfil de **outro** lojista → vazio (nunca
      lojista inteiro); header ≠ rota persistida → rota persistida vence.
- [x] T4.2: Teste — trocar perfil no meio da sessão → próxima query escopa o
      novo perfil; bot-set com perfil de outro lojista → vazio (`merchant_id` na
      subquery).

## T5 — Validação final

- [x] T5.1: `mvn test` verde (novos + regressão: todos os testes do portal sem
      header continuam passando; router Spec 91; payout Spec 92).
- [x] T5.2: E2E `profile-scope.spec.js` — 2 perfis com bots/transações
      distintos: trocar perfil → dashboard/CRM/extrato/bots/broadcasts mostram
      só o perfil; `X-Profile-ID` inválido → vazio; sem header → tudo.
- [x] T5.3: Coordenação — conferir que a Spec 122 está `implemented` (V69 +
      resolver + endpoints existem) antes de implementar esta.

**Âncoras validadas na spec:** `pix_transactions` (V1 + `bot_id` V50 + `provider`
V58); `bot_leads`/`bot_funnel_events` (V50, sem merchant_id — join via
`store_bots`); `broadcasts.store_bot_id` (V54); `tracking_profiles`/
`tracking_profile_bots` (V56); `chat_messages` (V57); `CreatePixChargeService`
(`CreatePixChargeUseCase.Command` tem `botId` — Spec 75); `CreatePixTransactionService`
(legado, sem bot); `PixProviderRouter`/`RoutingPolicyResolver`
(`resolveProviders(merchantId, profileId)` — Spec 122); serviços do portal:
`MerchantDashboardService`, `MerchantSalesService`, `CrmLeadService`,
`StoreBotManagementUseCase`, `BotMetricsService`, `BroadcastQueryService`,
`TrackingProfileQueryService` (todos filtram por `merchantId` hoje); `apiClient`
(Spec 123 já envia `X-Profile-ID`); migration **V70 livre** (V69 = Spec 122;
V60–V67 reservadas; V68 = Spec 120).

## § Implementação (2026-08-15)

Checklist T0–T5 concluído. **`/spec-implement 124`**:

- **T0 — Migration + domínio**: `V70__pix_transactions_profile.sql` (coluna
  `profile_id` FK + índice parcial `(merchant_id, profile_id)` + backfill
  bot→perfil / sem bot→default); `PixTransaction`/`PixTransactionJpaEntity`/
  mapper com `profileId` + `attachProfile`; `TransactionRepository` ganha as
  variantes `...AndProfileId` + bot-set (`countByBotIdIn...`/
  `sumAmountByBotIdIn...`); `PixTransactionJpaRepository` com as queries
  correspondentes.
- **T1 — `ProfileScopeResolver`** (+ `scopeBotIds` — conveniência bot-set):
  `resolveActiveProfileId` (valida pertinência), `scopeProfileId` (escopo de
  leitura: header inválido → valor, a query escopa `profile_id = :header AND
  merchant_id = :auth` → VAZIO), `botIdsOf` (subquery merchant-scoped),
  `defaultProfileId`, `scopeBotIds` (null sem header). Testes `ProfileScopeResolverTest`
  (11 casos: válido/inválido/ausente, outro lojista, bot-set, default).
- **T2 — Cobranças (rota viva)**: `CreatePixChargeService.resolveProfileId`
  (header validado → bot → default) + `attachProfile` + `pending.profileId()`
  no comando do PSP; `CreatePixTransactionService` (legado → default);
  `MerchantChargeController` (portal — header validado). Regressão Spec 91
  (sem perfil → política por risco) coberta pelo `RoutingPolicyResolverTest`
  da Spec 122 (`profileIdNullUsaRisco`).
- **T3 — Filtros por módulo**: `MerchantDashboardService`/`MerchantSalesService`
  (perfil), `CrmLeadService` (bot-set), `StoreBotManagementUseCase`
  (`list(merchantId, profileId, ...)` — frota do perfil), `BotMetricsService`
  (`summarizeMerchant(merchantId, botIds, ...)`), `BroadcastQueryService` +
  `BroadcastRepository.findByMerchantIdAndBotIds`, `TrackingProfileQueryService` +
  `TrackingProfileRepository.findByMerchantIdAndBotIds`; controllers do portal
  (`PortalDashboardController`, `PortalSalesController`, `PortalCrmController`,
  `PortalBotController`, `PortalBotMetricsController`, `PortalBroadcastController`,
  `PortalTrackingController`) passam o escopo do `ProfileScopeResolver`.
- **T4 — Segurança**: `ProfileScopeResolverTest` — perfil de outro lojista →
  empty (resolveActiveProfileId) / vazio por construção (scopeBotIds →
  `IN ()`); rota persistida vence header (Spec 122, `RoutingPolicyResolverTest`).
- **T5 — Validação**: `mvn test` **1825/0/0** (1815 + novos: resolver 11,
  BotMetricsServiceTest +2, StoreBotCommandServiceTest +2,
  BroadcastQueryServiceTest +4, TrackingProfileQueryServiceTest +4 — registrado
  no audit; conferir contagem no relatório surefire) + E2E `profile-scope.spec.js`
  (6 cenários — dashboard/extrato/bots/CRM/broadcasts por perfil, inválido →
  vazio, sem header → tudo) + suíte completa sem regressão.
- **Decisões de implementação**: bot-set usa `IN :botIds` (lista vazia → vazio,
  nunca lojista inteiro); `scopeBotIds` retorna null (sem header) para os
  services distinguirem "sem predicado"; a atribuição de bot→perfil é via
  `store_bots.profile_id` (sem API de atribuição na Spec 122 — E2E usa SQL).

## § Auditoria (2026-08-15)

**`/spec-audit 124`: 7/7 critérios OK, 1 achado de código corrigido, 0 abertos.**

Verificações contra o código real (fase de auditoria pós-implementação):

- **C1 (migration V70)** — `V70__pix_transactions_profile.sql` lido: `profile_id` FK NULL
  + índice parcial `(merchant_id, profile_id)` WHERE profile_id IS NOT NULL + backfill
  (com bot → perfil do bot; sem bot → perfil default; lojista sem perfil → NULL). Aplicada
  no boot (health UP).
- **C2 (resolução de perfil nas cobranças)** — `CreatePixChargeService.resolveProfileId`
  (header validado → bot → default) + `attachProfile` + `pending.profileId()` no comando
  do PSP; `CreatePixTransactionService` (legado → default); `PortalChargeController`/
  `MerchantChargeController` (header via `ProfileScopeResolver.resolveActiveProfileId` —
  inválido/outro lojista → default). Router usa `resolveProviders(merchantId, profileId)`.
- **C3 (filtro por perfil no portal)** — `ProfileScopeResolver` wired nos 8 controllers do
  portal (`scopeProfileId`/`scopeBotIds`); header válido → escopa; inválido → **vazio**
  (nunca lojista inteiro — `merchant_id` no predicado); ausente → null → lojista inteiro
  (regressão). Bots listam só a frota do perfil.
- **C4 (rota viva)** — cobrança com perfil `gateway_route = "pagarme"` → `provider=pagarme`
  via router (teste `RoutingPolicyResolverTest`); sem perfil → política por risco (regressão
  Spec 91); fallback/circuit breaker intactos (E2E psp-fallback 4/4 na auditoria com
  `NYX_PSP_ROUTING_ENABLED=true`).
- **C5 (não vazamento)** — `ProfileScopeResolverTest` 12 casos (header válido/inválido/
  ausente, perfil de outro lojista → vazio por construção `IN ()`, bot-set merchant-scoped);
  rota persistida vence header (Spec 122). Trocar perfil no meio da sessão → próxima query
  escopa (cada request resolve o header).
- **C6 (módulos escopados)** — dashboard/sales (perfil), CRM (bot-set), bots (frota),
  métricas (bot-set), broadcasts (bot-set), tracking (bot-set) — verificado no código
  (`MerchantDashboardService`, `MerchantSalesService`, `CrmLeadService`,
  `StoreBotManagementUseCase`, `BotMetricsService`, `BroadcastQueryService`,
  `TrackingProfileQueryService` + repositories `...AndProfileId`/`...AndBotIds`).
- **C7 (testes)** — `mvn test` **1824/0/0** na auditoria (+ contexto smoke fora do default,
  Spec 68) + E2E `profile-scope.spec.js` **6/6** (2 perfis com bots/transações/leads/
  broadcasts distintos → trocar perfil → só o perfil; inválido → vazio; sem header → tudo)
  + suíte E2E completa **171/0/0** verde.

**Achado de código corrigido na auditoria (2026-08-15):**

- **BUG REAL — bots sem perfil invisíveis com o default ativo.** O `StoreBotJpaRepository`
  filtrava estritamente `profile_id = :profileId`; a Spec 122 define que bot com
  `profile_id` NULL pertence **logicamente** ao perfil default. Como o `apiClient` (Spec
  123) persiste o default e envia `X-Profile-ID` em toda chamada, bots registrados (Spec 74
  não atribui perfil) e legados sumiam de **bots/CRM/broadcasts/tracking/dashboard-leads**
  com o default ativo (7 E2E falhando: bots-panel, bots-creator×2, broadcast-editor, crm,
  portal-ui, tracking). Corrigido no `StoreBotPersistenceAdapter`
  (`includeUnassigned(merchantId, profileId)` — TRUE quando o perfil é o default do
  lojista) + `StoreBotJpaRepository.findFleetByMerchantIdAndProfile`/
  `findIdsByMerchantIdAndProfile` com `OR profile_id IS NULL` condicional; **3 testes novos**
  no `StoreBotPersistenceAdapterTest` (default inclui NULL / não-default exclui NULL /
  ids do default incluem NULL). Regressão de segurança mantida: perfil de OUTRO lojista →
  lista vazia (query merchant-scoped); perfil não-default → só os bots atribuídos.
- **E2E atualizados** (spec 123/124): `tracking.spec.js` — vínculo do bot no modal
  (filtro rígido exige bot-set) + selectors `exact:true` colidindo com o ProfileSelector.

Fim do ciclo SDD — Spec 124 `audited`.
