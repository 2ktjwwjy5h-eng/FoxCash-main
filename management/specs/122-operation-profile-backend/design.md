---
name: Perfil de operação backend — entidade merchant_profiles (fase 1: fundação)
status: audited
---

# Spec 122 — Design: Perfil de operação (backend, fase 1)

## D1 — Conceito

O roteamento Pix resolve por lojista (`PixProviderRouter` recebe `merchantId`;
`RoutingPolicyResolver` escolhe por `risk_score` — Spec 91). Esta spec adiciona
**uma dimensão nova: o perfil de operação** — entidade `merchant_profiles`
(N por lojista). Cada perfil carrega `gateway_route` (override explícito,
resolvido **antes** da política de risco) e aponta para uma frota de bots
(`store_bots.profile_id`). O router não muda: continua chamando
`policyResolver.resolveProviders(...)`; o que muda é a assinatura (perfil
opcional) e a resposta do resolver.

### D1.1 Decisões do usuário (2026-08-15, revisadas nesta spec) → regras

| Decisão | Regra |
|---|---|
| N perfis por lojista (entidade) | `merchant_profiles` substitui a coluna `merchants.gateway_route` (Spec 98: coluna removida do V61) |
| Fase 1 = fundação (mecanismo) | override no resolver + endpoints + header prontos; call-sites passam `profileId` na fase 2 (Spec 124) |
| Filtro rígido na fase 2 | esta spec NÃO filtra dados por perfil; a Spec 124 escopa módulos (dashboard/financeiro/CRM/...) |
| Só a estrutura (adquirente depois) | sem adapter novo; `availableRoutes` = configurados + PADRAO |
| Só cobrança | `PixProviderRouter`; payout (Spec 92) inalterado |
| Header seleciona perfil, nunca decide rota | `X-Profile-ID` valida pertinência ao lojista; a rota vem do banco (anti-fraude) |
| Bot fleet por perfil | `store_bots.profile_id` (FK NULL; bot NULL = default do lojista) |

## D2 — Migration V69

`src/main/resources/db/migration/V69__merchant_profiles.sql`:

```sql
-- Spec 122: perfis de operação do lojista (seletor de contexto do portal).
-- gateway_route NULL/PADRAO = roteamento padrão por risco (RoutingPolicyResolver, Spec 91).

CREATE TABLE merchant_profiles (
    id            UUID PRIMARY KEY,          -- gerado pela app (padrão store_bots, V49)
    merchant_id   UUID        NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    name          VARCHAR(64) NOT NULL,
    gateway_route VARCHAR(32),               -- NULL = PADRAO (rota por risco)
    is_default    BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order    INT         NOT NULL DEFAULT 0,
    deleted_at    TIMESTAMPTZ,               -- soft-delete (padrão store_bots)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchant_profiles_merchant
    ON merchant_profiles (merchant_id) WHERE deleted_at IS NULL;

-- Backfill: 1 perfil default ("Padrão BR") por lojista existente.
INSERT INTO merchant_profiles (id, merchant_id, name, is_default, sort_order)
SELECT gen_random_uuid(), id, 'Padrão BR', TRUE, 0 FROM merchants;

-- Frota de bots por perfil: bots existentes → perfil default do lojista.
ALTER TABLE store_bots ADD COLUMN profile_id UUID REFERENCES merchant_profiles (id);
UPDATE store_bots b
   SET profile_id = mp.id
  FROM merchant_profiles mp
 WHERE mp.merchant_id = b.merchant_id AND mp.is_default;
```

- Sem CHECK em `gateway_route`: valores vêm do catálogo (`ProviderCatalog`, D5) na
  validação do PATCH/POST; no runtime, rota desconhecida → fallback + WARN (D4).
- `profile_id` **NULL permitido** (legado): na leitura, bot NULL → perfil default
  do lojista (regra lógica, sem segunda escrita).
- Número: **V69** livre (última real V59; V60–V67 reservadas — Specs 97/98/101–119;
  V68 — Spec 120).
- Lojistas criados **após** a migration ganham o perfil default na criação (service
  de registro) ou lazy no primeiro `GET /portal/profile` — decisão: registrar no
  fluxo de criação (Specs 5/13), com fallback lazy por defesa.

## D3 — Domínio e persistência

**`MerchantProfile`** (novo agregado leve, `domain/model/profile/`):

```java
public class MerchantProfile {
    private final UUID id;
    private final UUID merchantId;
    private String name;
    private String gatewayRoute; // null = PADRAO
    private boolean isDefault;
    private int sortOrder;

    public void rename(String name) { /* valida 1..64 */ }
    public void changeGatewayRoute(String route) {
        this.gatewayRoute = (route == null || route.isBlank() || "PADRAO".equalsIgnoreCase(route))
                ? null : route.toLowerCase();
    }
}
```

- `MerchantProfileJpaEntity` + `MerchantProfileMapper` (persistência).
- Port out **`MerchantProfileRepository`**:
  - `List<MerchantProfile> findByMerchantId(UUID merchantId)` (não-deletados, por `sort_order`);
  - `Optional<MerchantProfile> findById(UUID id)` (não-deletado);
  - `Optional<MerchantProfile> findByIdForUpdate(UUID id)` (SEC-19 — escrita);
  - `MerchantProfile save(MerchantProfile profile)`;
  - `Optional<UUID> defaultProfileIdOf(UUID merchantId)` (leitura leve, pro header);
  - `Optional<String> gatewayRouteOf(UUID merchantId, UUID profileId)` — `@Query`
    projection (hot path do resolver, sem carregar agregados).

## D4 — `RoutingPolicyResolver` com override por perfil

`resolveProviders(merchantId, profileId)` passa a ser:

```java
public List<String> resolveProviders(UUID merchantId, UUID profileId) {
    if (profileId != null) {
        Optional<String> override = profileRepository.gatewayRouteOf(merchantId, profileId);
        if (override.isPresent() && !override.get().isBlank()) {
            return List.of(override.get().toLowerCase());
        }
    }
    // ... política por risco atual (Spec 91) intacta
}

public String resolvePolicyName(UUID merchantId, UUID profileId) {
    if (profileId != null && profileRepository.gatewayRouteOf(merchantId, profileId).isPresent()) {
        return "override"; // tag da métrica nyx_psp_route_total
    }
    // ... política por risco atual
}
```

- **Sobrecarga de compatibilidade**: manter `resolveProviders(merchantId)`
  delegando com `profileId = null` → call-sites da fase 1 (cobrança sem perfil)
  seguem com a política por risco (regressão Spec 91 intacta).
- **Defesa em profundidade**: rota do perfil que não existe no catálogo
  configurado (ex.: `gateway_route = "stripe"` sem adapter/credencial) → **fallback
  para a política por risco + WARN** (nunca 500). O PATCH/POST já impedem gravar
  rota inválida (400); a checagem cobre dados legados/edição manual.
- `PixProviderRouter`, `PixProviderHealthRegistry`, fail-fast de credencial:
  **inalterados** (Spec 91).

## D5 — `ProviderCatalog` (catálogo de rotas)

`infrastructure/config/ProviderCatalog`:

```java
/** Rotas disponíveis para o perfil: "PADRAO" + adquirentes configurados. */
public List<String> availableRoutes() {
    Set<String> keys = new LinkedHashSet<>();
    keys.add("PADRAO");
    keys.add(psp.getProvider().toLowerCase());
    for (String k : routing.getProviders().split(",")) {
        if (!k.isBlank()) keys.add(k.trim().toLowerCase());
    }
    return List.copyOf(keys);
}
```

- Usado por: `GET /portal/profile` (availableRoutes de cada perfil) e
  `POST/PATCH /portal/profile` (validação). Reuso das mesmas configs do router
  (`foxcash.psp.provider` + `foxcash.psp.routing.providers`).
- **Nota**: `mock` aparece se configurado (dev); em prod só os reais configurados
  aparecem — sem adapter novo, hoje o catálogo lista PADRAO + pagarme/asaas (o
  que estiver configurado).

## D6 — Endpoints + header `X-Profile-ID`

`infrastructure/inbound/rest/OperationProfileController.java`
(`/api/v1/portal/profile`, `ROLE_MERCHANT_PORTAL`, prefixo no `VARIABLE_PREFIXES`
do `RateLimitKey` — padrão SEC-23/Spec 69):

- **`GET /api/v1/portal/profile`** → `{ profiles: ProfileResponse[],
  activeProfileId }`:
  - `ProfileResponse = { id, name, gatewayRoute ("PADRAO"|key), availableRoutes,
    bots }` — bots via `StoreBotManagementUseCase` (Spec 74) filtrados por
    `profile_id` (NULL → default); `availableRoutes` = `ProviderCatalog`.
  - `activeProfileId` = do header `X-Profile-ID` **se validado** (perfil
    não-deletado do lojista autenticado); senão → `defaultProfileIdOf(merchantId)`.
- **`POST /api/v1/portal/profile`** body `{ name, gatewayRoute? }`:
  1. `name` 1–64 chars (trim) → 400 `invalid_name` se vazio/ausente.
  2. `gatewayRoute` opcional: normaliza (`"PADRAO"`/null/blank → null); se setado,
     valida no `ProviderCatalog` → 400 `unknown_gateway_route`.
  3. `sort_order` = max+1 do lojista; se o lojista não tem nenhum perfil, o criado
     vira `is_default = true`.
  4. Auditoria `PROFILE_CREATED` (`{name}`); 201 + `ProfileResponse`.
- **`PATCH /api/v1/portal/profile/{id}`** body `{ name?, gatewayRoute? }`:
  1. `findByIdForUpdate(id)` → 404 se inexistente ou `merchantId ≠` principal
     (IDOR).
  2. `name` presente → `rename` (validação → 400 `invalid_name`).
  3. `gatewayRoute` presente → `changeGatewayRoute` (validação no catálogo →
     400 `unknown_gateway_route`).
  4. Auditoria: `PROFILE_UPDATED` (`{name}`) e/ou `GATEWAY_ROUTE_CHANGED`
     (`{gatewayRoute}`); 200 + `ProfileResponse`.

**Header `X-Profile-ID`**: a UI envia o perfil ativo como **seleção de contexto**.
O backend **valida a pertinência** (perfil não-deletado do lojista autenticado) e
usa para: (a) definir `activeProfileId` no `GET profile`; (b) (fase 2, Spec 124)
escopar consultas de módulos. **Nenhuma lógica de roteamento lê o header** — a
rota da cobrança vem da linha do perfil no banco, resolvida pelo
`RoutingPolicyResolver` com `profileId` explícito (fase 2). Teste de segurança
cobre "header com perfil de outro lojista → ignorado" e "header ≠ rota persistida
→ rota persistida vence".

## D7 — Auditoria

`PlatformAction` += `PROFILE_CREATED`, `PROFILE_UPDATED`, `GATEWAY_ROUTE_CHANGED`
(Spec 41). Trilha recebe apenas `{name}`/`{gatewayRoute}` (sem PII); `PADRAO` →
`"PADRAO"`.

## D8 — Mudanças de arquivo (lista finita)

| Arquivo | Mudança |
|---|---|
| `src/main/resources/db/migration/V69__merchant_profiles.sql` | novo (D2) |
| `domain/model/profile/MerchantProfile.java` | novo (D3) |
| `domain/port/out/MerchantProfileRepository.java` | novo (D3) |
| `infrastructure/outbound/persistence/entity/MerchantProfileJpaEntity.java` + `MerchantProfileMapper` | novo |
| `infrastructure/outbound/persistence/repository/MerchantProfileJpaRepository.java` | novo (@Query projections) |
| `infrastructure/outbound/persistence/adapter/MerchantProfilePersistenceAdapter.java` | novo |
| `infrastructure/outbound/pix/RoutingPolicyResolver.java` | override por perfil (D4) + sobrecarga |
| `infrastructure/config/ProviderCatalog.java` | novo (D5) |
| `infrastructure/inbound/rest/OperationProfileController.java` + DTOs | novo (D6) |
| `domain/model/activity/PlatformAction.java` | + `PROFILE_CREATED`, `PROFILE_UPDATED`, `GATEWAY_ROUTE_CHANGED` |
| `infrastructure/outbound/persistence/entity/StoreBotJpaEntity.java` + mapper | + `profileId` |
| `management/specs/98-painel-usuarios-config/task.md` | coordenação: `gateway_route` removida do V61 (feito) |

**Sem mudança:** `PixProviderRouter`, `PixProviderHealthRegistry`,
`RoutingProperties`, `PayoutProviderRouter` (Spec 92), adapters,
`pix_transactions`, `Merchant`/`MerchantJpaEntity` (a rota saiu do `merchants` —
a Spec 98 não adiciona a coluna).

## D9 — Testes

- **`RoutingPolicyResolverTest`** — override do perfil vence risco; PADRAO/null →
  risco; `profileId` null → risco (regressão); rota fora do catálogo → fallback +
  WARN; `resolvePolicyName` = `"override"`.
- **`OperationProfileControllerTest`** — GET (profiles/activeProfileId/bots por
  perfil), POST (cria/`invalid_name`/`unknown_gateway_route`/1º perfil vira
  default), PATCH (sucesso PADRAO→null/rota persistida/`invalid_name`/404 IDOR),
  RBAC (ROLE_MERCHANT_PORTAL), lock (SEC-19), auditoria chamada.
- **Migration/backfill** — lojista com bots → 1 perfil default + bots no default;
  lojista sem bots → 1 perfil default; `gen_random_uuid()` no INSERT SELECT
  (padrão já usado no V16).
- **Regressão router (Spec 91)** — cobrança sem perfil → política por risco atual;
  fallback/circuit breaker intactos (testes existentes verdes).
- **Segurança** — `X-Profile-ID` com perfil de outro lojista → `GET profile` usa
  default; header nunca altera a rota persistida.

## D10 — Riscos e mitigação

- **Coluna `gateway_route` duplicada (98 vs 122)** → **removida do V61 da 98**
  (coordenação no task); a rota vive só em `merchant_profiles` (dona: esta spec).
- **Config quebrada no perfil** (rota sem adapter) → fallback para risco + WARN no
  resolver (nunca 500); PATCH/POST validam o catálogo.
- **Header como vetor de fraude** → header só seleciona perfil do próprio lojista
  (server-validado) e nunca decide rota; teste de segurança fixa o invariante.
- **Bots legados sem perfil** → regra lógica: bot NULL = default do lojista; sem
  dado órfão.
- **Precedência com o master (Spec 98)** → rota agora é por perfil e self-service
  do lojista; o master console não gerencia perfil nesta fase (não há coluna
  `merchants.gateway_route` para conflitar).
