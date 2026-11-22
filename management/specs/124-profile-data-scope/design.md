---
name: Perfil de operação (fase 2) — filtro rígido de dados por perfil
status: audited
---

# Spec 124 — Design: Perfil de operação (fase 2, filtro rígido)

## D1 — Conceito

A fase 1 (Specs 122/123) deu a fundação: `merchant_profiles`, `store_bots.profile_id`,
seletor no HeaderGlobal e header `X-Profile-ID`. Esta fase **ativa o filtro
rígido**: o header (validado) define o **perfil ativo**, e cada query do portal
escopa os dados por perfil. A **rota do gateway vira viva**: as cobranças passam
`profileId` para o `RoutingPolicyResolver` (override do perfil vence risco).

**Cadeia de resolução** (fonte da verdade = banco):

```
bot (store_bots.profile_id)  ──► perfil
transação (pix_transactions.profile_id)  ──► perfil      [coluna nova, V70]
leads/funil/broadcasts/chat/tracking/métricas  ──► bot ──► perfil   [join]
```

**Regra de escopo (unificada):**

```
sem X-Profile-ID           → escopo lojista inteiro (regressão / clientes legados)
X-Profile-ID válido        → escopo = perfil (dados do perfil)
X-Profile-ID inválido      → escopo = perfil, mas WHERE merchant_id = auth →
                             resultado VAZIO (nunca vaza; nunca cai p/ lojista inteiro)
```

## D2 — Migration V70

`src/main/resources/db/migration/V70__pix_transactions_profile.sql`:

```sql
-- Spec 124: perfil de operação da transação (filtro rígido por perfil do portal).
-- V69 (Spec 122) cria merchant_profiles + store_bots.profile_id.

ALTER TABLE pix_transactions ADD COLUMN profile_id UUID REFERENCES merchant_profiles (id);
CREATE INDEX ix_pix_transactions_profile ON pix_transactions (merchant_id, profile_id)
    WHERE profile_id IS NOT NULL;

-- Backfill: bot → perfil do bot; sem bot → perfil default do lojista.
UPDATE pix_transactions t
   SET profile_id = b.profile_id
  FROM store_bots b
 WHERE t.bot_id = b.id AND b.profile_id IS NOT NULL;

UPDATE pix_transactions t
   SET profile_id = mp.id
  FROM merchant_profiles mp
 WHERE t.profile_id IS NULL
   AND t.merchant_id = mp.merchant_id
   AND mp.is_default;
```

- `profile_id` NULL permitido (dado legado de lojista sem perfil — anterior à
  V69): só aparece no escopo "sem header" (lojista inteiro).
- Número: **V70** livre (V69 = Spec 122; V60–V67 reservadas; V68 = Spec 120).

## D3 — `ProfileScopeResolver` (novo, infraestrutura)

`infrastructure/inbound/rest/ProfileScopeResolver.java` — resolve o escopo do
header para os controllers do portal:

```java
/** Perfil ativo do header (validado) — vazio se ausente ou não pertence ao lojista. */
Optional<UUID> resolveActiveProfileId(UUID merchantId, HttpServletRequest request);

/** Ids dos bots do perfil (para escopo por bot-set). */
List<UUID> botIdsOf(UUID profileId);

/** Perfil default do lojista (fallback das cobranças sem bot/header). */
UUID defaultProfileId(UUID merchantId);
```

- `resolveActiveProfileId`: lê `X-Profile-ID`, busca o perfil não-deletado por
  `id + merchantId` (`MerchantProfileRepository.findByIdAndMerchantId`); ausente
  ou não pertence → `Optional.empty()` (a query então escopa por `profile_id =
  header` mesmo assim, com `merchant_id = auth`, resultando vazio — ver D5).
- `botIdsOf`: `SELECT id FROM store_bots WHERE profile_id = :profileId AND
  deleted_at IS NULL` (cache curto-TTL opcional — sem cache na v1, o volume é
  baixo; medir se precisar).

## D4 — Cobranças com `profileId` (rota viva)

Três call-sites resolvem o `profileId` na **criação** da cobrança e gravam
`pix_transactions.profile_id`:

| Fluxo | Origem do perfil |
|---|---|
| `CreatePixChargeService` (bot, `POST /api/v1/foxcash/pix/cob`) | bot_id → `store_bots.profile_id`; sem bot → `defaultProfileId(merchantId)` |
| `MerchantChargeController` (`/api/v1/portal/charges`) | header `X-Profile-ID` (validado); ausente → default |
| `CreatePixTransactionService` (API legada, `POST /api/v1/transactions`) | `defaultProfileId(merchantId)` |

Cada um passa o `profileId` para:
`routingPolicyResolver.resolveProviders(merchantId, profileId)` → `List<String>`
(override do perfil vence risco — Spec 122) → `PixProviderRouter` inalterado. A
transação é criada com `profileId` + o `provider` efetivo (V58, já gravado).

- `PixTransaction`/`PixTransactionJpaEntity`/mapper: campo `profileId`.
- **Regressão**: fluxos que não passam perfil (nulos) seguem a política por
  risco (Spec 91) — os testes existentes devem continuar verdes.

## D5 — Filtro por módulo (services do portal)

Padrão: os controllers do portal passam `Optional<UUID> profileId` (do
`ProfileScopeResolver`) para os services; cada service adiciona o predicado ao
filtro `merchantId` já existente.

| Service/query | Predicado de perfil |
|---|---|
| `MerchantDashboardService` | transações: `profile_id = :profileId` (variantes dos métodos `countByMerchantId...`/`findApprovedByMerchantIdBetween`/`findByMerchantId` com `AndProfileId`); atenção (leads): `bot_id IN :botIds` |
| `MerchantSalesService` | `profile_id = :profileId` (lista/métricas/detalhe — `findByMerchantIdAndProfileId`) |
| `CrmLeadService` | `bot_id IN :botIds` (`search(merchantId, profileId, ...)` — join natural via `store_bots`) |
| `StoreBotManagementUseCase` | `store_bots.profile_id = :profileId` (lista a frota do perfil) |
| `BotMetricsService` | `summarizeMerchant(merchantId, profileId)` — soma métricas dos bots `IN :botIds` |
| `BroadcastQueryService` | `store_bot_id IN :botIds` |
| `TrackingProfileQueryService` | perfis com `tracking_profile_bots.bot_id IN :botIds` (existe `tracking_profiles` por lojista; filtra os cujos bots são do perfil) |

- Implementação concreta dos filtros: ou variantes `@Query` (ex.:
  `...AndProfileId`), ou um predicado JPA `Specification` quando o repo já usa
  (`CrmLeadRepository.search`). **Decisão**: variantes de método quando o repo é
  Spring Data derived; `@Query` explícita com parâmetro opcional quando a query
  já é JPQL. Registrar na implementação.
- **Sem header** → `profileId` vazio → predicados ignorados (lojista inteiro).
- **Header inválido** → `profileId` = o valor do header (mesmo inválido) →
  predicado `profile_id = :header AND merchant_id = :auth` → **vazio** (sem
  vazamento). Para bot-set, botIds de um perfil inexistente = lista vazia →
  `bot_id IN ()` → vazio. Seguro por construção.

## D6 — Segurança

- O `ProfileScopeResolver` **valida pertinência** (perfil do lojista autenticado)
  apenas para decidir o escopo; a query sempre inclui `merchant_id = auth`
  (nenhum cruzamento de tenant é possível).
- O header **nunca decide rota** — a rota vem da linha do perfil no banco
  (Spec 122). Teste de segurança: header com perfil de outro lojista → vazio;
  header ≠ rota persistida → rota persistida vence.
- O escopo por bot-set usa `store_bots.merchant_id` na subquery (`profile_id`
  é global) — um perfil de outro lojista não retorna bots.

## D7 — Auditoria

Sem eventos novos (as escritas de perfil são da fase 1). A auditoria existente
(`PROFILE_*`/`GATEWAY_ROUTE_CHANGED`, Spec 122) cobre as mudanças de
configuração; o filtro é **leitura** (não gera trilha).

## D8 — Mudanças de arquivo (lista finita)

| Arquivo | Mudança |
|---|---|
| `src/main/resources/db/migration/V70__pix_transactions_profile.sql` | novo (D2) |
| `infrastructure/inbound/rest/ProfileScopeResolver.java` | novo (D3) |
| `domain/model/pix/PixTransaction.java` + `PixTransactionJpaEntity` + mapper | + `profileId` |
| `application/service/CreatePixChargeService.java` | resolve perfil (bot→perfil/default) + passa ao resolver + grava profile_id |
| `application/service/CreatePixTransactionService.java` | idem (default) |
| `infrastructure/inbound/rest/MerchantChargeController.java` (se existir separado) | perfil do header |
| `application/service/MerchantDashboardService.java` + repo queries | filtro por perfil |
| `application/service/MerchantSalesService.java` + repo queries | idem |
| `application/service/CrmLeadService.java` + `CrmLeadRepository` | bot-set |
| `application/use_case/StoreBotManagementUseCase.java` | frota do perfil |
| `application/service/BotMetricsService.java` | bot-set |
| `application/service/BroadcastQueryService.java` + repo | bot-set |
| `application/service/TrackingProfileQueryService.java` + repo | bot-set |
| controllers do portal (`PortalDashboardController`, `PortalSalesController`, `PortalCrmController`, `PortalBotController`, `PortalBotMetricsController`, `PortalBroadcastController`, `PortalTrackingController`) | passam `profileId` do `ProfileScopeResolver` |

**Sem mudança:** `PixProviderRouter`, `PixProviderHealthRegistry`, adapters,
payout (Spec 92), frontend (o `apiClient` da Spec 123 já envia o header).

## D9 — Padrão para módulos futuros (Specs 109–119)

Quando redirectores/filtro de tráfego (109/110), ranking (113/114), checkout/biolink
(115/116), automações/remarketing (117/118) e afiliado (111/112) forem
implementados, o escopo por perfil **não é refeito**: as tabelas novas devem
carregar `bot_id`/`profile_id` (ou serem vinculáveis a bot), e o controller usa
o `ProfileScopeResolver` + o predicado do D5. **Regra de contrato:** toda query
do portal que retorna dado operacional (transação, lead, link, fluxo, ranking)
deve aceitar `Optional<UUID> profileId` e escopar quando presente.

## D10 — Testes

- **Migration/backfill** — transação com bot → perfil do bot; sem bot → default;
  lojista sem perfil → NULL; índice criado.
- **`ProfileScopeResolverTest`** — header válido/inválido/ausente; perfil de
  outro lojista → empty; `botIdsOf`/`defaultProfileId`.
- **Cobranças** — bot flow grava `profile_id` do bot e roteia pela rota do
  perfil; portal charge usa o header; API legada → default; sem perfil →
  política por risco (regressão Spec 91).
- **Filtros por módulo** — dashboard/sales (profile_id), CRM/broadcasts/
  tracking/métricas (bot-set), bots (frota); sem header → lojista inteiro;
  header inválido → vazio.
- **Segurança** — header com perfil de outro lojista → vazio; rota persistida
  vence header; trocar perfil na sessão → próxima query escopa o novo perfil.
- **E2E** (`profile-scope.spec.js`) — 2 perfis com bots/transações distintos:
  trocar perfil → dashboard/CRM/extrato/bots/broadcasts mostram só o perfil;
  `X-Profile-ID` inválido → vazio; sem header → tudo.

## D11 — Riscos e mitigação

- **Transações legadas sem perfil** (lojista sem perfil pós-V69) → NULL:
  visíveis só no escopo "sem header"; nunca escondidas para clientes legados.
- **Performance do bot-set** (muitos bots) → `bot_id IN (subquery)` com índice
  em `store_bots(profile_id)`; volume baixo na v1 (sem cache; medir se precisar).
- **Header inválido mostrando vazio** (confuso para o usuário) → a UI da fase 1
  já tem sanidade (perfil removido → default) e o `apiClient` só envia perfis da
  lista — caso de borda para clientes manuais; documentado na spec.
- **Regressão sem header** → todos os testes existentes do portal continuam
  passando (predicados ausentes = comportamento atual).
