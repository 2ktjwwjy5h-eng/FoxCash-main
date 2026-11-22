---
name: Perfil de operação backend — entidade merchant_profiles (fase 1: fundação)
status: audited
references:
  - "management/vision.md#V89"
  - "management/specs/91-fallback-adquirente/requirements.md"
  - "management/specs/98-painel-usuarios-config/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
---

# Spec 122 — Perfil de operação (backend, fase 1: fundação)

## O que entrega

A fundação do **seletor de contexto de perfil** do portal (decisão do usuário
2026-08-15, revisada nesta spec): cada lojista passa a ter **N perfis de
operação** — entidade nova `merchant_profiles` — e cada perfil carrega a **rota
de gateway** (cobrança Pix) e a **frota de bots** (escopo de dados). **Fase 1 =
mecanismo completo, estrutura apenas**: o filtro rígido de dados por perfil nos
módulos do portal chega na **Spec 124 (fase 2, planejada)**. Nenhum adapter novo
(Stripe/Global é futuro) — o seletor fica pronto e passa a valer quando um 2º
adquirente real for configurado.

1. **Entidade `merchant_profiles`** (migration **V69**, dona desta spec): `id UUID`,
   `merchant_id UUID NOT NULL`, `name VARCHAR(64) NOT NULL`,
   `gateway_route VARCHAR(32)` (NULL/PADRAO = roteamento por risco), `is_default
   BOOLEAN`, `sort_order INT`, soft-delete (`deleted_at`), timestamps. **Backfill**:
   um perfil `"Padrão BR"` (default) por lojista existente; **bots existentes →
   perfil default do lojista** (`store_bots.profile_id`).
2. **Frota de bots por perfil** — `store_bots.profile_id UUID NULL REFERENCES
   merchant_profiles(id)`. Bot sem perfil (legado/NULL) pertence **logicamente**
   ao perfil default do lojista. Âncora de dados da Spec 124.
3. **Override no roteamento por perfil** — `RoutingPolicyResolver.resolveProviders(
   merchantId, profileId)`: perfil com `gateway_route` fixado → `[rota]`; senão →
   política por `risk_score` (Spec 91, regressão intacta). `profileId` null
   (call-sites da fase 1 ainda sem perfil) → comportamento atual.
4. **Endpoints do portal** (ROLE_MERCHANT_PORTAL):
   - `GET /api/v1/portal/profile` → `{ profiles: [ProfileResponse...],
     activeProfileId }`; `ProfileResponse = { id, name, gatewayRoute,
     availableRoutes, bots }`; `activeProfileId` = header `X-Profile-ID`
     (validado: perfil não-deletado do lojista autenticado) ou o default.
   - `POST /api/v1/portal/profile` `{ name, gatewayRoute? }` → cria perfil
     (valida rota no catálogo → 400 `unknown_gateway_route`; `name` 1–64 → 400
     `invalid_name`; primeiro perfil do lojista vira default).
   - `PATCH /api/v1/portal/profile/{id}` `{ name?, gatewayRoute? }` → atualiza
     (404 se inexistente/de outro lojista; 400 rota fora do catálogo; escrita
     sob lock do lojista — SEC-19; auditoria `GATEWAY_ROUTE_CHANGED`/
     `PROFILE_UPDATED`).
5. **Segurança — fonte da verdade é o banco**: o header `X-Profile-ID`
   **seleciona o perfil ativo entre os perfis do próprio lojista** (server-
   validado; perfil de outro lojista → ignorado). A rota da cobrança vem da
   linha do perfil no banco, nunca do header — um lojista não pode se auto-
   rotear para adquirente fora da lista configurada.
6. **Auditoria** — `PlatformAction` += `PROFILE_CREATED`, `PROFILE_UPDATED`,
   `GATEWAY_ROUTE_CHANGED` (Spec 41), trilha com `{name}`/`{gatewayRoute}`
   apenas (sem PII).
7. **Fora de escopo (fase 1)**: filtro rígido de dados por perfil (Spec 124);
   call-sites de cobrança passando `profileId` (fase 2 — hoje o router recebe
   null e segue risco); adapter Stripe novo; payout (`PayoutProviderRouter`
   inalterado); master console (Spec 98 — a coluna `gateway_route` prevista no
   V61 da 98 **é removida**: a rota vive no perfil).

## Contexto (por que agora)

O roteamento de cobrança já é dinâmico por lojista (`PixProviderRouter`, Spec 91
`audited`) com circuit breaker e `pix_transactions.provider` gravando a rota
efetiva (V58). O usuário decidiu (2026-08-15, revisado) que o seletor de contexto
do portal deve trocar o **perfil de operação** — não apenas uma rota por lojista:
**N perfis por lojista**, cada um com rota de gateway + frota de bots + (fase 2)
dados escopados. Esta spec entrega a **fundação**: a entidade, o override no
resolver por perfil, os endpoints e o header `X-Profile-ID` (seleção de perfil
server-validada). O filtro rígido nos módulos (dashboard, financeiro, CRM, bots,
redirectores, remarketing, tracking) é a **Spec 124**.

## Critérios de aceite

1. **Migration V69** — `merchant_profiles` (campos da seção 1) +
   `store_bots.profile_id` (FK NULL) + backfill: 1 perfil default por lojista
   (`"Padrão BR"`); bots existentes apontados para o default do seu lojista;
   lojista novo sem perfil → default criado no fluxo de registro (fallback lazy
   no primeiro `GET profile`).
2. **Resolver com override por perfil** — `resolveProviders(merchantId,
   profileId)`: perfil com rota setada → `List.of(rota)`; PADRAO/null → política
   por risco; `profileId` null → política por risco (regressão Spec 91); rota sem
   adapter/credencial → fallback para risco + WARN. `resolvePolicyName` →
   `"override"` quando o override do perfil está ativo (métrica
   `nyx_psp_route_total`).
3. **`GET /api/v1/portal/profile`** — 200 com `profiles` (cada: id, name,
   gatewayRoute `"PADRAO"|key`, availableRoutes `["PADRAO",...]` de
   `PspProperties.getProvider()` + `RoutingProperties.getProviders()` dedupe,
   bots = não-deletados do perfil — NULL → default) e `activeProfileId` (do
   header validado ou default; perfil de outro lojista no header → ignorado →
   default).
4. **`POST /api/v1/portal/profile`** — `{ name }` obrigatório (1–64, trim → 400
   `invalid_name`); `gatewayRoute` opcional validado no catálogo (400
   `unknown_gateway_route`); cria com `sort_order` = max+1; primeiro perfil do
   lojista vira default; auditoria `PROFILE_CREATED`; 201 + resposta.
5. **`PATCH /api/v1/portal/profile/{id}`** — `{ name?, gatewayRoute? }`; 404 se
   inexistente/de outro lojista (IDOR); rota fora do catálogo → 400
   `unknown_gateway_route`; `"PADRAO"`/null → null; escrita sob
   `findByIdForUpdate` (SEC-19); auditoria `GATEWAY_ROUTE_CHANGED`/
   `PROFILE_UPDATED` (`{name}`/`{gatewayRoute}`); 200 + resposta.
6. **Header `X-Profile-ID`** — **seleciona o perfil, nunca decide rota**.
   Nenhuma lógica de roteamento lê o header. Teste de segurança: header com
   perfil de outro lojista → ignorado (usa default); rota persistida do perfil
   vence.
7. **Testes** — `mvn test` verde: resolver (override vence risco; PADRAO→risco;
   profileId null→risco; rota sem adapter→fallback+WARN), controller (GET/POST/
   PATCH, RBAC, IDOR, unknown_gateway_route, invalid_name, PADRAO→null, lock,
   auditoria), migração/backfill, regressão router (Spec 91: fallback/circuit
   breaker intactos).

## Dependências

- **Spec 91** (router) — `PixProviderRouter`/`RoutingPolicyResolver`/
  `RoutingProperties`/`PixProviderHealthRegistry` (override é um branch no
  resolver com `profileId` opcional; o router não muda).
- **Spec 98** (config master) — **coordenação**: a coluna `gateway_route`
  prevista no V61 da 98 **é removida** (a rota vive em `merchant_profiles`, dona:
  esta spec). Nota atualizada no task da 98.
- **Spec 41** (trilha) — `PlatformAction` += `PROFILE_CREATED`,
  `PROFILE_UPDATED`, `GATEWAY_ROUTE_CHANGED`.
- **Spec 74** (bots) — `StoreBotManagementUseCase`/`store_bots` (FK `profile_id`;
  query de bots por perfil).
- **Spec 123** (UI) — consome `GET/POST/PATCH /api/v1/portal/profile` e envia
  `X-Profile-ID` (depende desta).
- **Spec 124** (fase 2, planejada) — filtro rígido por perfil nos módulos;
  call-sites de cobrança passam `profileId`.

## Fora de escopo

- **Filtro rígido de dados por perfil** — Spec 124 (fase 2).
- **Roteamento vivo por perfil** — call-sites de cobrança (bot/checkout/API)
  passando `profileId`: fase 2; hoje o router recebe null e segue a política por
  risco (regressão intacta).
- **Adapter Stripe/Global** — só o mecanismo; com um 2º adquirente real, o switch
  passa a valer sem mudança de arquitetura.
- **Payout** — `PayoutProviderRouter` (Spec 92) inalterado (só cobrança).
- **Vínculo automático bot→perfil** — o lojista associa bots a perfis na fase 2
  (UI de gerenciamento); a fase 1 só garante o FK + backfill.
- **Master console** — o painel do master (Spec 98/100) não gerencia perfis nesta
  fase.
- **Header como fonte de decisão de rota** — explícito: nunca.
