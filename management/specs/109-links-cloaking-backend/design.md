---
name: Gestão de Links e Campanhas — links rastreáveis, domínios, UTMs e filtro de tráfego (backend)
status: verified
---

# Spec 109 — Design: Gestão de Links (backend)

## D1 — Conceito

Domínio `TrafficLink` + serviço `TrafficLinkService` (CRUD/métricas) e
`TrafficRedirectService` (redirect público com filtro de tráfego). O redirect é **público
e sem auth** (posse do slug = acesso), com rate limit por IP.

```text
PortalLinkController (ROLE_MERCHANT_PORTAL) — CRUD links/domains/sale-codes/stats
PublicTrafficController (público)           — GET /l/{slug} (redirect + filtro de tráfego)
   └─ TrafficLinkService / TrafficRedirectService
        ├─ CloakerDecider (UA/IP/ref → is_bot)
        ├─ TrafficClickRecorder
        └─ (Spec 111) sale_code attribution
```

## D2 — Migration V62

```sql
CREATE TABLE traffic_domains (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  domain VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE sale_codes (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  code VARCHAR(32) NOT NULL, label VARCHAR(120) NULL, affiliate_id UUID NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (merchant_id, code));
CREATE TABLE traffic_links (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  name VARCHAR(120) NOT NULL, destination_url VARCHAR(500) NOT NULL,
  domain_id UUID NULL REFERENCES traffic_domains(id), slug VARCHAR(16) NOT NULL UNIQUE,
  sale_code VARCHAR(32) NULL REFERENCES sale_codes(code), utm_source VARCHAR(120) NULL,
  utm_medium VARCHAR(120) NULL, utm_campaign VARCHAR(120) NULL, utm_term VARCHAR(120) NULL,
  utm_content VARCHAR(120) NULL, filtro de tráfego_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  intermediate_page VARCHAR(500) NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  click_count BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE traffic_clicks (id UUID PK, link_id UUID NOT NULL REFERENCES traffic_links(id),
  clicked_at TIMESTAMPTZ NOT NULL, ip_hash VARCHAR(64) NULL, user_agent VARCHAR(500) NULL,
  referer VARCHAR(500) NULL, is_bot BOOLEAN NOT NULL DEFAULT FALSE,
  resolved_url VARCHAR(500) NULL);
ALTER TABLE pix_transactions ADD COLUMN sale_code VARCHAR(32) NULL; -- atribuição (Spec 111)
CREATE INDEX ix_traffic_links_merchant ON traffic_links (merchant_id, status);
CREATE INDEX ix_traffic_clicks_link ON traffic_clicks (link_id, clicked_at);
```

**V62** (última real: V61 — Spec 98).

## D3 — Endpoints do portal

### D3.1 Links

- `GET /api/v1/portal/links?status=&filtro de tráfego=&q=&page=&size=` (cap 100) →
  `PageResponse<TrafficLinkResponse>`: `{id, name, destinationUrl, domain,
  slug, url (base-url/l/{slug}), saleCode, utm*, filtro de tráfegoEnabled,
  intermediatePage, status, clickCount, createdAt}`.
- `POST` → 201 (gera slug único 6-8 chars; valida destination SSRF-safe via
  `WebhookUrlValidator`; domínio do lojista).
- `PATCH /{id}` → 200 (parcial); `DELETE /{id}` → 204 (desativa e preserva
  trilha — KISS); `POST /{id}/activate|deactivate` → 204.
- 404 `link_not_found` cross-lojista; 400 `invalid_argument`; 409
  `invalid_state`.

### D3.2 Domínios

- `GET/POST/DELETE /api/v1/portal/links/domains` — máx. 5 ativos (409
  `too_many_domains`); valida formato do domínio.

### D3.3 Sale codes

- `GET/POST/PATCH/DELETE /api/v1/portal/links/sale-codes` — `code` único por
  lojista (409 `sale_code_already_exists`); `affiliateId` (Spec 111).

### D3.4 Stats

- `GET /api/v1/portal/links/{id}/stats` → `{clicks, sales, revenue,
  conversionRate}` — clicks = COUNT `traffic_clicks`; sales = COUNT
  `pix_transactions` APPROVED com `sale_code` do link; revenue = Σ.

## D4 — Redirect público `GET /l/{slug}`

- Sem auth; lookup por `slug` (404 genérico — sem revelar existência).
- **CloakerDecider** (`is_bot(ua, ip, ref, isPreview)`):
  - `?preview=1` → sempre humano (teste).
  - Regex de UA de bot das redes sociais (`facebookexternalhit`, `Twitterbot`,
    `Discordbot`, `Slackbot`, `LinkedInBot`, `TelegramBot`, `Pinterest`,
    `TikTok` fetch) + `Sec-Fetch-Dest: document` humano.
  - Bot → **página limpa** (HTML estático neutro do domínio) com `200` (não
    301 — o robô não deve seguir); humano → 301 para `intermediate_page` (se
    houver) ou `destination_url`.
- Registra `traffic_clicks` (INSERT) com `is_bot`; `click_count` do link += 1
  (UPDATE atômico).
- Rate limit por IP em `/l/**` (Spec 43/59 — teto generoso, ex.: 60/min).
- `sale_code`/UTMs propagadas: na URL de destino, monta query com `sale_code` +
  `utm_*` originais + `shk={slug}` (a venda entra atribuída).
- `resolved_url` gravada (o que o humano recebeu).

## D5 — Preparar campanha

`POST /api/v1/portal/links/prepare-campaign` `{domainId, destinationUrl,
utmSource, utmMedium, utmCampaign, utmTerm, utmContent, filtro de tráfegoEnabled,
intermediatePage, saleCode}` → valida e cria o link (mesma lógica do POST) —
retorna a URL camuflada pronta. (Decisão: cria direto; o "preparar" é o fluxo
do modal da Spec 110.)

## D6 — Auditoria + métricas

- `PlatformAction` += `LINK_CREATED/LINK_UPDATED/LINK_DEACTIVATED/
  DOMAIN_ADDED/DOMAIN_REMOVED/SALE_CODE_CREATED/...` (seção `// Tráfego (Spec
  109)`).
- Métricas `nyx_traffic_clicks_total`, `nyx_traffic_links_total` (Spec 53).

## D7 — Testes

**Unitários:** `CloakerDeciderTest` (UAs de bot, preview, humano),
`TrafficLinkServiceTest` (CRUD, slug único, validações, 404 cross-lojista),
`TrafficRedirectServiceTest` (301/200, clique registrado, rate limit).
**Integração:** controllers. **E2E** (`portal-links.spec.js`): criar link →
copiar URL → `GET /l/{slug}` com UA de humano → 301 + clique; com UA de bot →
200 página limpa + `is_bot`; criar sale_code → cobrança com `sale_code` →
stats refletem.
