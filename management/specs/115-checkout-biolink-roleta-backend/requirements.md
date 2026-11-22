---
name: Checkout + Bio Link + Cupons & Sorteios — páginas customizadas de conversão (backend)
status: verified
references:
  - "management/vision.md#V84"
  - "management/specs/23-pix-charge-generator/requirements.md"
  - "management/specs/48-stores-storefront/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
---

# Spec 115 — Checkout + Bio Link + Cupons & Sorteios: páginas customizadas de conversão (backend)

## O que entrega

Backend dos módulos **Checkout** (construtor de páginas de pagamento
customizadas), **Bio Link** (árvore de links) e **Cupons & Sorteios** (sorteio
gamificada de incentivo à conversão) do portal (descrição de produto do usuário
2026-08-15).

1. **`checkout_pages`** (V65): `id, merchant_id, slug, title, product_name,
   product_price, colors (JSON: primária/botão), logo_url, button_text,
   sorteio_enabled (bool), status, created_at/updated_at` — página pública
   `GET /c/{slug}` (render server-side dos dados; sem HTML editável — construtor
   de dados, não de código — KISS e seguro).
2. **`bio_link_pages`** (V65): `id, merchant_id, slug, title, avatar_url,
   bio, theme (cor), status` + **`bio_link_items`**: `id, page_id, label,
   url, sort` — página pública `GET /bio/{slug}` (árvore de links).
3. **`sorteio_config`** (V65): `id, merchant_id, checkout_page_id (nullable —
   global ou por página), prizes (JSON: [{label, probability}]), status`.
4. **Página pública de checkout** (`GET /c/{slug}`): renderiza o produto e
   gera o **Pix na hora** via `PixProviderClient` (Spec 23/91) com o
   `sale_code`/UTMs do query (reuso Spec 109) — a cobrança nasce vinculada.
5. **Endpoints do portal** (ROLE_MERCHANT_PORTAL): CRUD `checkout_pages`,
   CRUD `bio_link_pages` + itens, CRUD `sorteio_config`; métricas de views
   (`checkout_views` contador).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Checkouts (métricas globais +
construtor), Bio Link (árvore de links) e Cupons & Sorteios (sorteio gamificada).
Lacuna: nada existe (o "checkout" atual é a cobrança avulsa do portal, Spec 23;
as páginas públicas customizadas são novas).

## Critérios de aceite

1. **Migration V65** (uma): `checkout_pages`, `bio_link_pages`,
   `bio_link_items`, `sorteio_config` — FKs `merchant_id`, `slug` UNIQUE,
   índices. **V65** (V64 = Spec 113).
2. **CRUD checkouts**: `GET/POST/PATCH/DELETE /api/v1/portal/checkouts` —
   slug único (409 `slug_already_exists`), validação de cores (`#RRGGBB`),
   `product_price > 0`, ativar/desativar. **Métricas**: `GET
   /portal/checkouts/{id}/stats` → `{views, sales, revenue}` (views = contador;
   sales/revenue = pix_transactions com `checkout_page_id`).
3. **Página pública `GET /c/{slug}`**: 200 HTML (server-side — Thymeleaf/
   template simples ou JSON consumido por uma rota pública da SPA? — decisão:
   **rota pública JSON `GET /api/v1/public/checkout/{slug}`** + a SPA do portal
   tem uma rota pública `/c/:slug` que renderiza — reusa o `PixProviderClient`
   via `POST /api/v1/foxcash/pix/cob` com API key do lojista? — decisão de
   implementação: o checkout público usa o **fluxo da Spec 23** com a
   credencial do lojista em contexto server-side). A sorteio (se ativa) renderiza
   o prêmio.
4. **CRUD bio links**: `GET/POST/PATCH/DELETE /api/v1/portal/biolinks` +
   `POST/PATCH/DELETE /biolinks/{id}/items` (reorder `sort`).
5. **Página pública `GET /bio/{slug}`**: 200 JSON `{page, items[]}` para a
   rota pública `/bio/:slug` da SPA.
6. **Sorteio**: `GET/POST/PATCH/DELETE /api/v1/portal/sorteios` — prizes com
   probabilidades somando 100 (400 `invalid_argument`); `spin` server-side:
   `POST /api/v1/portal/sorteios/{id}/spin` → `{prize}` (peso aleatório — não
   manipula resultados; opcional: verificar se há checkout ativo antes).
7. **Sem HTML/script editável** — construtor de dados (anti-XSS; CSP mantido).
8. **Segurança**: sem PII em logs; rate limit; contratos existentes
   inalterados.
9. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 23** (cobrança) — geração do Pix no checkout público.
- **Spec 109** (links) — `sale_code`/UTMs no checkout.
- **Spec 48** (storefront) — padrão de página pública/JSON.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — a página pública
  `/c/{slug}`/`/bio/{slug}` gera o Pix **sob o perfil do contexto** (Spec
  124): gravar `profile_id` (V70) na cobrança e resolver a rota por perfil
  (`RoutingPolicyResolver`, Spec 122/139) — `BOT_ONLY` → rota por risco,
  `GATEWAY_ONLY` → rota explícita, sem frota.

## Fora de escopo

- **UI dos editores** — Spec 116.
- **Editor HTML/JS customizado** — construtor de dados (segurança).
- **Sorteio com prêmio real automático** — o spin sorteia e mostra; o desconto/
  prêmio real aplicado no pagamento = evolução.
