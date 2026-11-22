---
name: Checkout + Bio Link + Cupons & Sorteios — páginas customizadas de conversão (backend)
status: verified
---

# Spec 115 — Design: Checkout + Bio Link + Sorteio (backend)

## D1 — Conceito

Três agregados (CheckoutPage, BioLinkPage, SorteioConfig) com CRUD no portal e
**rotas públicas JSON** consumidas por páginas públicas da SPA (padrão
storefront Spec 48). Construtor de **dados** (sem HTML editável — anti-XSS).

```text
PortalCheckoutController (ROLE_MERCHANT_PORTAL) — CRUD checkouts/biolinks/sorteios + spin
PublicCheckoutController (público)              — GET /api/v1/public/checkout/{slug}
PublicBioController (público)                   — GET /api/v1/public/bio/{slug}
   └─ CheckoutService / BioLinkService / SorteioService
        ├─ PixProviderClient (Spec 23/91) — gera o Pix do checkout
        └─ (Spec 109) sale_code/UTMs propagados
```

## D2 — Migration V65

```sql
CREATE TABLE checkout_pages (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  slug VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(120) NOT NULL,
  product_name VARCHAR(120) NOT NULL, product_price NUMERIC(15,2) NOT NULL,
  colors JSONB NULL, logo_url VARCHAR(500) NULL, button_text VARCHAR(60) NULL,
  sorteio_enabled BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  views BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE bio_link_pages (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  slug VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(120) NOT NULL, avatar_url VARCHAR(500) NULL,
  bio VARCHAR(500) NULL, theme VARCHAR(16) NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE bio_link_items (id UUID PK, page_id UUID NOT NULL REFERENCES bio_link_pages(id) ON DELETE CASCADE,
  label VARCHAR(120) NOT NULL, url VARCHAR(500) NOT NULL, sort INT NOT NULL DEFAULT 0);
CREATE TABLE sorteio_config (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  checkout_page_id UUID NULL REFERENCES checkout_pages(id), prizes JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL);
```

**V65** (V64 = Spec 113).

## D3 — Endpoints do portal

- **Checkouts**: `GET/POST/PATCH/DELETE /api/v1/portal/checkouts` (slug único,
  409; cores `#RRGGBB`; price > 0); `POST /{id}/activate|deactivate`;
  `GET /{id}/stats` `{views, sales, revenue}` (views da coluna; sales/revenue
  por `pix_transactions.checkout_page_id` — coluna aditiva `V65 ALTER`).
- **Bio links**: `GET/POST/PATCH/DELETE /api/v1/portal/biolinks` +
  `POST /biolinks/{id}/items`, `PATCH/DELETE /biolinks/items/{itemId}`,
  `PUT /biolinks/{id}/order {ids[]}`.
- **Sorteio**: `GET/POST/PATCH/DELETE /api/v1/portal/sorteios` (prizes com
  probabilidades somando 100 — 400); `POST /{id}/spin` → `{prize}` (peso
  aleatório via `ThreadLocalRandom` + `Clock`).

## D4 — Páginas públicas

- **`GET /api/v1/public/checkout/{slug}`** → `{page, sorteio?, saleCode?,
  utms?}` — sem o Pix embutido (a SPA gera via `POST /nyx/pix/cob` com a
  credencial do lojista — decisão de implementação: **o checkout público usa o
  fluxo da Spec 23** com `merchant_id` resolvido do slug; a cobrança grava
  `checkout_page_id` + `sale_code`). `views` incrementada.
- **`GET /api/v1/public/bio/{slug}`** → `{page, items[]}` (ordenado por `sort`).
- Rate limit por IP nas rotas públicas (Spec 43/59); **sem auth** (posse do
  slug = acesso público, padrão storefront).

## D5 — Sorteio

`spin`: pesos por probabilidade → sorteio server-side (não manipulável pelo
cliente); devolve `{prize}`; a SPA anima. Sem desconto aplicado automaticamente
(evolução).

## D6 — Auditoria + métricas

`PlatformAction` += `CHECKOUT_CREATED/UPDATED/...`, `BIOLINK_*`, `SORTEIO_*`.
Métricas `nyx_checkout_views_total`.

## D7 — Testes

**Unitários:** `CheckoutServiceTest` (CRUD, slug único, validação de cor/price,
stats), `BioLinkServiceTest` (itens + reorder), `SorteioServiceTest` (prizes
soma 100, spin dentro das probabilidades). **Integração:** controllers +
públicas (200/404/rate limit). **E2E** (`portal-checkout.spec.js`): criar
checkout → página pública → gerar Pix (sale_code) → stats; criar biolink →
página pública; sorteio spin.

### D7.1 — Não ignorar a Spec 139 (perfil do contexto)

A página pública `/c/{slug}`/`/bio/{slug}` gera o Pix **sob o perfil do
contexto** (Spec 124): a cobrança grava `profile_id` (V70) e resolve a rota via
`RoutingPolicyResolver` (Spec 122/139). Perfil `BOT_ONLY` → rota por risco
(nunca override); `GATEWAY_ONLY` → rota explícita, sem frota. `sale_code`/UTMs
do link (Spec 109) permanecem vinculados à venda.

## D8 — Fluxo de dados do checkout público (registro de design 2026-08-16)

Detalhamento da Spec 115 D4 — como o Gateway (`/c/{slug}`, ADR-020) gera o Pix
e como a venda chega de volta à página.

```
Cliente abre /c/{slug}
   │
   ▼
[1] GET /api/v1/public/checkout/{slug}          (público, rate limit por IP)
   │  → resolve merchant_id pelo slug (404 checkout_not_found se ausente/
   │    status != ACTIVE); incrementa views
   │  → devolve {page, sorteio?, saleCode?, utms?}  (SEM Pix embutido)
   ▼
[2] SPA renderiza produto (estado "pronto") — nenhuma cobrança nasce ainda
   │
   ▼
[3] Cliente toca em comprar
   │  → POST /api/v1/public/checkout/{slug}/charge      (aditivo da Spec 115)
   │  → reusa o fluxo da Spec 23 (PixProviderClient) com a credencial do
   │    MERCHANT dono do checkout (contexto server-side — nunca expõe a
   │    API key do lojista ao cliente)
   │  → grava pix_transactions.checkout_page_id + sale_code/UTMs (Spec 109)
   │  → devolve {transactionId, pixQrCode, pixCopyPaste, status, expiresAt}
   ▼
[4] SPA mostra QR + Copia e Cola + countdown (estado "aguardando")
   │
   ▼
[5] Webhook /webhooks/pix (SALE_PAID — pipeline Specs 10/28/35)
   │  → crédito no ledger/wallet (pipeline financeiro — Specs 27/30)
   │  → outbox de notificação → entrega (Specs 49/52 — mesmo motor dos bots)
   ▼
[6] SPA (polling curto ou SSE do status) reflete PAID → estado "confirmado"
   │  → mostra chave de acesso + entrega na própria página
   ▼
[7] Stats: GET /portal/checkouts/{id}/stats → {views, sales, revenue}
      (sales/revenue = pix_transactions com checkout_page_id)
```

Regras de consistência:
- **Nunca gerar cobrança no GET** (estado [1]) — evita cobrança fantasma e
  contam views por carga; o Pix nasce só no `charge` ([3]).
- **Confirmação é reflexo do webhook** — a SPA NUNCA confirma no cliente; o
  polling [6] apenas espelha o status persistido pelo `ProcessPixWebhookService`.
- **Sorteio**: spin server-side (`POST /portal/sorteios/{id}/spin`, peso
  aleatório) — o prêmio é informativo nesta fase (desconto real = evolução).
- **Segurança**: sem API key do lojista no cliente; sem HTML editável
  (construtor de dados — anti-XSS); rate limit por IP nas rotas públicas
  (Specs 43/59); `sale_code`/UTMs propagados para atribuição (Spec 109/111).
