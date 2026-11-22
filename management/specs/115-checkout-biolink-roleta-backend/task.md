---
name: Checkout + Bio Link + Cupons & Sorteios — páginas customizadas de conversão (backend)
status: verified
---

# Spec 115 — Task: Checkout + Bio Link + Sorteio (backend)

## F0 — Migration + domínio

- [ ] **T0.1** — Migration **V65** (D2): `checkout_pages`, `bio_link_pages`,
      `bio_link_items`, `sorteio_config` + `ALTER pix_transactions ADD
      checkout_page_id` (aditivo).
- [ ] **T0.2** — Entidades JPA + mappers + portas (`CheckoutPageRepository`,
      `BioLinkPageRepository`, `SorteioConfigRepository`).

## F1 — Services

- [ ] **T1.1** — `CheckoutService`: CRUD (slug único, cores, price), stats
      (views/sales/revenue), `publicPage(slug)` (incrementa views).
- [ ] **T1.2** — `BioLinkService`: CRUD páginas + itens + reorder.
- [ ] **T1.3** — `SorteioService`: CRUD (prizes soma 100), `spin` (peso
      aleatório).

## F2 — Controllers

- [ ] **T2.1** — `PortalCheckoutController` (`/api/v1/portal/checkouts`,
      `/biolinks`, `/sorteios`, `/sorteios/{id}/spin`).
- [ ] **T2.2** — `PublicCheckoutController` (`GET /api/v1/public/checkout/{slug}`)
      + `PublicBioController` (`GET /api/v1/public/bio/{slug}`) — públicos,
      rate limit por IP (Spec 43/59), permitAll no `SecurityConfig`.
- [ ] **T2.3** — `PlatformAction` += `CHECKOUT_*`/`BIOLINK_*`/`SORTEIO_*`; handler
      de 409 `slug_already_exists`/400 `invalid_argument`.
- [ ] **T2.4** — Cobrança do checkout público grava `profile_id` (V70) do
      contexto e resolve a rota por perfil (`RoutingPolicyResolver`, Spec
      122/139 — `BOT_ONLY` → risco, `GATEWAY_ONLY` → rota explícita).

## F3 — Testes + E2E

- [ ] **T3.1** — Unitários/integração (D7) + `mvn test` verde.
- [ ] **T3.2** — E2E `portal-checkout.spec.js` + roadmap/status.

## Verificação (`/spec-verify 115`)

- [ ] Checar âncoras (`PixProviderClient`, `CreatePixChargeService`,
      `WebhookUrlValidator`, `SecurityConfig` permitAll, `RateLimitFilter`,
      `QueryParamParser`) — sem achados.
