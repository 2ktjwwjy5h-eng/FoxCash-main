# Spec 48 — Task breakdown

Status: `audited` (2026-08-05). Ciclo SDD completo.

## Implementação

- [x] **Migration V36 (parte 1)** — tabela `stores` (id/merchant_id UNIQUE/
      name/logo_url/banner_url/description/social_links/color/emoji/title/
      footer/created_at/updated_at).
- [x] **Domínio** — `Store` (create/restore/update; validações nome/URLs/cor/
      social_links) + `StoreRepository` (save/findByMerchantId/
      findByIdAndMerchantId/existsByMerchantId).
- [x] **Painel do vendedor (portal, ROLE_MERCHANT_PORTAL)**
  - [x] `StoreService` + `PortalStoreController`:
        `GET/POST/PATCH /api/v1/portal/store` (404 sem loja; POST duplicado →
        409 `store_already_exists`); `MerchantAccessGuard`; 400 `invalid_argument`.
- [x] **Storefront (API key do lojista)**
  - [x] `StorefrontService` + `GET /api/v1/foxcash/storefront` — DTO agregado
        (identidade + botões Spec 50 + planos Spec 49 + mensagens Spec 51),
        ordenado; 404 `store_not_found`.
- [x] **Atividade/logs** — `PlatformAction` += STORE_CREATED/STORE_UPDATED;
      mascaramento de campos sensíveis nos logs (Spec 41).
- [x] **Testes** — `StoreTest` (validações), `StoreServiceTest` (409/404/200),
      `StorefrontServiceTest` (agregação, 404), `PortalStoreControllerTest`,
      `FoxStorefrontControllerTest`; E2E cenário novo (criar loja → storefront →
      409 duplicada → 404).

## Verificação

- [x] `mvn test` verde (**520/0/0**) e E2E **23/23** (cenário 23 novo) sem
      regressão.
- [x] Smoke ao vivo: criar loja no portal → `GET /nyx/storefront` com a config.

## Verificação (`/spec-verify`, 2026-08-05)

- [x] Consistência verificada: 1 loja/merchant (modelagem 1:N); storefront
      agrega identidade + botões (Spec 50) + planos (Spec 49) + mensagens
      (Spec 51); 404 `store_not_found`; 409 duplicada.
- [x] Achados aplicados: nenhum (docs consistentes). Status → `verified`.

## Auditoria do ciclo SDD (2026-08-05)

- [x] Critérios de aceite 1–5 verificados ao vivo (smoke + E2E 23/23).
- [x] Cross-cutting fechado: validação de entrada (domínio → 400
      `invalid_argument`, inclusive `socialLinks` com item sem label/url —
      nunca 500), rate limit no storefront (Spec 43, GET por IP+rota, 429 após
      o teto — verificado ao vivo), dados mascarados em logs (Spec 41),
      auditoria `STORE_CREATED`/`STORE_UPDATED` na mesma transação (Spec 41),
      acesso por recurso (principal == merchantId, 404 cross-loja sem vazar
      existência).
- [x] Achados corrigidos: **2** — (1) `SocialLink` com label/url nulos virava
      `NullPointerException` (500) em payload inválido; agora
      `IllegalArgumentException` (400 `invalid_argument`); (2) storefront fora
      do rate limiter (Spec 43); agora GET por IP+rota (20/min default, 429
      verificado). Aceitos por design: `StoreRequest` sem Bean Validation (a
      validação é do domínio — PATCH parcial exige "null = não alterar");
      storefront 1:1 por merchant nesta versão.
- [x] Status → `audited`.

## Follow-up de flakiness (2026-08-13) — 2ª ocorrência documentada

**Recorrência do flake pré-existente** (1ª ocorrência documentada na auditoria da
Spec 71 — `management/specs/71-master-console/task.md` e `roadmap.md`): durante a
verificação de step-up (Spec 72) com a suíte E2E completa (122 testes, **0 falhas
de step-up**), o **teste 23** desta spec (`merchant-journey.spec.js`, linha 2062)
falhou 1× de novo: `expect(patched.updatedAt).not.toBe(created.updatedAt)` — race
de precisão de timestamp (CREATE e PATCH no mesmo microssegundo; `Instant.now()`/
µs no backend — `StoreService` com `Clock`). Sem relação com a Spec 72 (arquivo da
Spec 48, pré-existente; a jornada isolada com banco limpo passa 27/27). Segue
**sem bloquear**; correção de teste (ex.: pequeno sleep entre CREATE e PATCH
mantendo a asserção estrita) permanece como follow-up aberto desta spec.
