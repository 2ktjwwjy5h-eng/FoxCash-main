---
name: Gestão de Links e Campanhas — links rastreáveis, domínios, UTMs e filtro de tráfego (backend)
status: verified
---

# Spec 109 — Task: Gestão de Links (backend)

## F0 — Migration + domínio

- [ ] **T0.1** — Migration **V62** (D2): `traffic_links`, `traffic_domains`,
      `traffic_clicks`, `sale_codes` + `ALTER pix_transactions ADD sale_code`.
- [ ] **T0.2** — Entidades JPA + mappers + portas (`TrafficLinkRepository`,
      `TrafficDomainRepository`, `TrafficClickRepository`,
      `SaleCodeRepository`).
- [ ] **T0.3** — `PlatformAction` += `LINK_*`/`DOMAIN_*`/`SALE_CODE_*` (seção
      `// Tráfego (Spec 109)`).

## F1 — Service (CRUD + stats)

- [ ] **T1.1** — `TrafficLinkService`: CRUD links (slug único, SSRF-safe,
      404 cross-lojista), activate/deactivate, CRUD domínios (máx 5, 409),
      CRUD sale-codes (único por lojista), `stats(linkId)`.
- [ ] **T1.2** — `prepareCampaign(...)` — valida UTMs e cria o link (D5).

## F2 — Redirect público + filtro de tráfego

- [ ] **T2.1** — `CloakerDecider` (`is_bot(ua, ip, ref, preview)` — regex de
      bots + Sec-Fetch-*).
- [ ] **T2.2** — `TrafficRedirectService`: `redirect(slug, request)` — bot →
      200 página limpa; humano → 301 (intermediate/destination) com `sale_code`/
      UTMs na query; INSERT `traffic_clicks` + UPDATE `click_count`.
- [ ] **T2.3** — `PublicTrafficController` (`GET /l/{slug}`, público, rate
      limit por IP — Spec 43/59, permitAll no `SecurityConfig`).

## F3 — Controllers portal

- [ ] **T3.1** — `PortalLinkController` (`/api/v1/portal/links`,
      `/links/domains`, `/links/sale-codes`, `/links/{id}/stats`,
      `/links/prepare-campaign`), ROLE_MERCHANT_PORTAL, escopo por lojista.
- [ ] **T3.2** — `GlobalExceptionHandler` += 404 `link_not_found`/`domain_not_found`/
      `sale_code_not_found`, 409 `too_many_domains`/`sale_code_already_exists`.

## F4 — Testes + E2E

- [ ] **T4.1** — Unitários/integração (D7) + `mvn test` verde.
- [ ] **T4.2** — E2E `portal-links.spec.js` (D7) + roadmap/status.

## Verificação (`/spec-verify 109`)

- [ ] Checar âncoras (`WebhookUrlValidator`, `PlatformAction`, `SecurityConfig`
      permitAll, `RateLimitFilter`, `QueryParamParser`) — sem achados.
