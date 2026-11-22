---
name: Perfil de operação backend — entidade merchant_profiles (fase 1: fundação)
status: audited
---

# Spec 122 — Task: Perfil de operação (backend, fase 1)

Ordem: T0 (migration/domínio) → T1 (resolver/override) → T2 (endpoints) → T3
(auditoria/segurança) → T4 (testes/coordenação).

## T0 — Migration + domínio (critérios 1, 3)

- [x] T0.1: **`V69__merchant_profiles.sql`** (D2) — tabela `merchant_profiles`
      (id UUID gerado pela app; merchant_id FK CASCADE; name VARCHAR(64);
      gateway_route VARCHAR(32) NULL; is_default; sort_order; deleted_at;
      created_at; updated_at) + índice parcial por merchant + **backfill**
      (`INSERT SELECT gen_random_uuid(), id, 'Padrão BR', TRUE, 0 FROM
      merchants`) + `store_bots.profile_id` (FK NULL) + `UPDATE store_bots SET
      profile_id = perfil default do lojista`. Confirmar V69 livre na hora da
      implementação (V60–V68 reservadas pelas Specs 97/98/101–119/120).
- [x] T0.2: `MerchantProfile` (domínio, `domain/model/profile/`) +
      `MerchantProfileJpaEntity` + `MerchantProfileMapper` (`rename` 1–64;
      `changeGatewayRoute` normaliza PADRAO/blank → null, lowercase).
- [x] T0.3: `MerchantProfileRepository` (port) + `MerchantProfileJpaRepository`
      (@Query projections: findByMerchantId, findById, findByIdForUpdate, save,
      defaultProfileIdOf, gatewayRouteOf(merchantId, profileId)) +
      `MerchantProfilePersistenceAdapter`.
- [x] T0.4: `StoreBotJpaEntity` + mapper — campo `profileId` (UUID, null
      permitido).

## T1 — Override no roteamento (critérios 2, 5)

- [x] T1.1: `RoutingPolicyResolver` — `resolveProviders(merchantId, profileId)` e
      `resolvePolicyName(merchantId, profileId)`: override do perfil presente e
      válido → `[route]`; senão política por risco (regressão); **sobrecarga**
      `resolveProviders(merchantId)` delegando com null (call-sites atuais da
      fase 1). **Validação contra o catálogo**: rota fora → fallback para risco +
      WARN (nunca 500).
- [x] T1.2: `ProviderCatalog.availableRoutes()` (D5) — reuso de
      `foxcash.psp.provider` + `foxcash.psp.routing.providers`.
- [x] T1.3: Testes — `RoutingPolicyResolverTest`: override do perfil vence risco;
      PADRAO → risco; profileId null → risco; rota fora do catálogo →
      fallback+WARN; regressão Spec 91.

## T2 — Endpoints do portal (critérios 3, 4, 5)

- [x] T2.1: `OperationProfileController` (`/api/v1/portal/profile`,
      ROLE_MERCHANT_PORTAL; prefixo no `VARIABLE_PREFIXES` do `RateLimitKey`):
      `GET` (profiles com id/name/gatewayRoute/availableRoutes/bots por perfil —
      NULL → default; activeProfileId do header validado ou default), `POST
      {name, gatewayRoute?}` (cria; 1º perfil do lojista vira default; valida
      catálogo → 400 `unknown_gateway_route`/`invalid_name`), `PATCH /{id}
      {name?, gatewayRoute?}` (404 IDOR; valida catálogo; `findByIdForUpdate` +
      save — SEC-19; auditoria; 200 atualizado).
- [x] T2.2: DTOs (`ProfileResponse`, `CreateProfileRequest`,
      `UpdateProfileRequest`).
- [x] T2.3: Testes — `OperationProfileControllerTest`: GET (campos,
      activeProfileId com/sem header, header de outro lojista → default), POST
      (sucesso/invalid_name/unknown_gateway_route/1º default), PATCH
      (sucesso/PADRAO→null/400/404 IDOR), RBAC, lock, auditoria.

## T3 — Auditoria + segurança (critérios 4, 6)

- [x] T3.1: `PlatformAction` += `PROFILE_CREATED`, `PROFILE_UPDATED`,
      `GATEWAY_ROUTE_CHANGED`; trilha `{name}`/`{gatewayRoute}` (sem PII).
- [x] T3.2: Javadoc no controller — `X-Profile-ID` seleciona perfil (validado),
      **nunca decide rota**; nenhuma lógica de roteamento o lê.
- [x] T3.3: Teste de segurança — `X-Profile-ID` com perfil de outro lojista →
      `GET profile` usa default; header ≠ rota persistida → rota persistida vence.

## T4 — Validação final

- [x] T4.1: `mvn test` verde (novos + regressão Spec 91: fallback/circuit
      breaker/fail-fast intactos; payout Spec 92 intacto).
- [ ] T4.2: E2E (`profile.spec.js`): criar 2º perfil via portal → `GET profile`
      reflete; PATCH rota do perfil → rota persistida; trocar perfil → header
      `X-Profile-ID` presente; bots do perfil listados. **(bloqueado: depende da
      Spec 123 — UI do seletor; os endpoints já são cobertos pelos testes de
      controller/service.)**
- [x] T4.3: Coordenação — conferir que a Spec 98 T0.1 **removeu** `gateway_route`
      do V61 (nota atualizada).

**Âncoras validadas na spec:** `PixProviderRouter` (`routingKeys(merchantId)` →
`RoutingPolicyResolver`; `@Primary`; fail-fast; circuit breaker por lojista);
`RoutingPolicyResolver` (políticas por `risk_score`; `resolvePolicyName`);
`RoutingProperties`/`PspProperties` (fontes do catálogo); `pix_transactions.provider`
(V58); `store_bots` (V49: id UUID, merchant_id UUID, soft-delete — FK `profile_id`);
`StoreBotManagementUseCase`/`GET /api/v1/portal/bots` (Spec 74); `gen_random_uuid()`
(já usado no V16); `findByIdForUpdate` (SEC-19); `PlatformAction` (valores novos);
migrations V60–V68 reservadas → **V69**; Spec 98 T0.1 (V61 — `gateway_route`
removida).

## § Auditoria (2026-08-15)

**`/spec-audit 122`: 7/7 critérios OK, 0 achados abertos.**

Verificações contra o código real (fase de auditoria pós-implementação):

- **C1 (migration V69)** — `V69__merchant_profiles.sql` lido: tabela `merchant_profiles`
  (id UUID, merchant_id FK CASCADE, name VARCHAR(64), gateway_route VARCHAR(32) NULL,
  is_default, sort_order, deleted_at, created_at, updated_at) + índice parcial por
  merchant + backfill `INSERT SELECT gen_random_uuid(), id, 'Padrão BR', TRUE, 0 FROM
  merchants` + `store_bots.profile_id` FK NULL + `UPDATE store_bots` → default.
  V69 aplicada no boot (app subiu com health UP; V69/V70 após V59 no repo).
- **C2 (resolver com override)** — `RoutingPolicyResolver.resolveProviders(merchantId,
  profileId)` + `resolvePolicyName(merchantId, profileId)`: override do perfil → `[rota]`
  quando no catálogo; PADRAO/null → política por risco (regressão); rota fora do catálogo
  → fallback para risco + WARN (defesa em profundidade). `resolvePolicyName` →
  `"override"` quando ativo. Sobrecarga single-arg delegando com null preservada.
- **C3 (GET /api/v1/portal/profile)** — `OperationProfileController` + `OperationProfileService`:
  `profiles[]` com id/name/gatewayRoute (`"PADRAO"|key`)/availableRoutes (`ProviderCatalog`:
  PADRAO + `foxcash.psp.provider` + routing.providers dedupe)/bots (perfil → default para
  NULL); `activeProfileId` do header validado (pertinência) ou default; perfil de outro
  lojista no header → ignorado (default). `ensureDefaultProfile` lazy cobre lojista sem
  perfil (dado anterior à V69).
- **C4 (POST)** — `{name}` obrigatório 1–64 trim (400 `invalid_name`); `gatewayRoute`
  validado no catálogo (400 `unknown_gateway_route`); `sort_order` = max+1; 1º perfil vira
  default; auditoria `PROFILE_CREATED` (Spec 41) na mesma transação; 201.
- **C5 (PATCH /{id})** — parcial (`null` = não altera), 404 IDOR (`findByIdForUpdate` +
  filtro merchantId → `profile_not_found`), rota fora do catálogo → 400, `"PADRAO"`/null →
  null; escrita sob lock pessimista (SEC-19); auditoria `GATEWAY_ROUTE_CHANGED`/
  `PROFILE_UPDATED` com `{gatewayRoute}`/`{name}` (sem PII); 200.
- **C6 (header X-Profile-ID)** — javadoc + código: o header **seleciona o perfil, nunca
  decide rota**; `ProfileScopeResolver`/`OperationProfileService` validam pertinência;
  `PixProviderRouter` lê `profileId` do **comando** (banco), não do header. Teste de
  segurança: perfil de outro lojista → default (nunca vaza); rota persistida vence.
- **C7 (testes)** — `mvn test` verde na implementação (1799) e re-executado na auditoria
  (**1824/0/0** — default sem o smoke `@Tag("context")` que requer Postgres, Spec 68).
  `RoutingPolicyResolverTest` +3 (override vence risco / fallback fora do catálogo /
  profileId null → risco), `OperationProfileControllerTest` (5), `OperationProfileServiceTest`
  (7). Router Spec 91 (fallback/circuit breaker/fail-fast) e payout Spec 92 intactos
  (suíte E2E completa verde na fase de auditoria).

**Achados corrigidos durante a auditoria (2026-08-15, fase de verificação E2E da Spec 124):**

1. **BUG REAL (afeta Specs 122/123/124 juntas)** — a frota/bot-set por perfil excluía bots
   com `profile_id` NULL mesmo quando o perfil ativo era o **default** do lojista. A Spec 122
   define "Bot sem perfil pertence logicamente ao perfil default" — bots registrados (Spec 74
   não atribui perfil) e legados ficavam invisíveis na UI com o default ativo (o `apiClient`
   da Spec 123 persiste o default no localStorage e envia `X-Profile-ID` em toda chamada).
   **Corrigido na camada de persistência** (`StoreBotPersistenceAdapter` + 
   `StoreBotJpaRepository.findFleetByMerchantIdAndProfile`/`findIdsByMerchantIdAndProfile` com
   `includeUnassigned=true` quando o perfil é o default do lojista) + 3 testes novos no
   `StoreBotPersistenceAdapterTest` (default inclui NULL / não-default exclui NULL / ids do
   default incluem NULL). Detalhe no § Auditoria da Spec 124.
2. **Testes E2E atualizados** para o novo contexto do ProfileSelector (Spec 123): selectors
   com `exact:true` onde a sidebar colidiu com botões da página (tracking "Criar perfil") e
   vínculo do bot no modal de tracking (filtro rígido da Spec 124 exige bot-set).

Fim do ciclo SDD — Spec 122 `audited`.
