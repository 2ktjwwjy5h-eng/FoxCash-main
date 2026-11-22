---
name: Gestão de Links e Campanhas — links rastreáveis, domínios, UTMs e filtro de tráfego (backend)
status: verified
references:
  - "management/vision.md#V81"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/49-planos-entregas/requirements.md"
---

# Spec 109 — Gestão de Links e Campanhas: links rastreáveis, domínios, UTMs e filtro de tráfego (backend)

## O que entrega

Backend do módulo **Gestão de Links e Campanhas (Distribuição)** do portal
(descrição de produto do usuário 2026-08-15): centralizar o **controle de
tráfego**, o **rastreamento (UTMs)** e a **proteção dos links (filtro de tráfego)** para
proteger as contas de anúncios (Meta/TikTok/Kwai) — **decisão do usuário:
controle de tráfego documentado** (página intermediária + filtro server-side;
não é evasão de revisão).

1. **`traffic_links`** (V62): `id, merchant_id, name, destination_url,
   domain_id, slug (código curto `shk=...`/`/{slug}`), sale_code (nullable —
   base do Afiliado Spec 111), utm_source/medium/campaign/term/content,
   filtro de tráfego_enabled, intermediate_page (nullable — lander), status
   (ACTIVE/INACTIVE), click_count, created_at/updated_at`.
2. **`traffic_domains`** (V62): `id, merchant_id, domain, status` — domínios do
   lojista usados para camuflar o destino (ex.: `paylume.fans`).
3. **`traffic_clicks`** (V62): `id, link_id, clicked_at, ip, user_agent,
   referer, is_bot (decisão do filtro de tráfego), resolved_url` — trilha de cada clique
   (inteligência do filtro de tráfego).
4. **`sale_codes`** (V62): `id, merchant_id, code, label, affiliate_id
   (nullable — Spec 111), active, created_at` — códigos de venda para
   atribuição de comissão/parceiro.
5. **Endpoints do portal** (ROLE_MERCHANT_PORTAL):
   - CRUD de links (`GET/POST/PATCH/DELETE /api/v1/portal/links`) + ativar/
     desativar + copiar URL camuflada.
   - CRUD de domínios (`GET/POST/DELETE /api/v1/portal/links/domains`).
   - CRUD de `sale_codes` (`GET/POST/PATCH/DELETE /api/v1/portal/links/sale-codes`).
   - Métricas por link (`GET /api/v1/portal/links/{id}/stats`): cliques, vendas,
     receita, conversão (cliques → vendas).
6. **`GET /l/{slug}`** — endpoint público de redirect: conta o clique, aplica o
   **filtro de tráfego server-side** (robô da rede social → página limpa; humano →
   destino/landing), valida `sale_code` para atribuição (Spec 111), registra o
   clique em `traffic_clicks`. Sem auth (público), rate limit por IP.
7. **Preparar campanha** (`POST /api/v1/portal/links/prepare-campaign`):
   estrutura o link com UTMs (origem/mídia/campanha) — valida e monta a URL
   camuflada final.
8. Auditoria (Spec 41 — `PlatformAction.LINK_CREATED/UPDATED/...`) + métricas
   `nyx_traffic_*`.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Gestão de Links e Campanhas do
portal de referência: meus links, códigos de venda, preparar campanha (UTM),
domínios, página intermediária, filtro de tráfego. Lacuna: nada disso existe (o Tracking
Spec 81/82 é pixels/UTM para ads server-side, **diferente** de links
rastreáveis com domínio próprio).

## Critérios de aceite

1. **Migration V62** (uma): `traffic_links`, `traffic_domains`,
   `traffic_clicks`, `sale_codes` — FKs `merchant_id`, índices
   (`traffic_links(merchant_id, status)`, `traffic_clicks(link_id, clicked_at)`,
   `sale_codes(merchant_id, code)` unique). Número **V62** (última real V61 —
   Spec 98).
2. **CRUD links**: `GET /portal/links` (paginado, filtros status/filtro de tráfego/q),
   `POST` (201 com a URL camuflada `{base-url}/l/{slug}`), `PATCH` (parcial),
   `DELETE` (soft? decisão: hard-delete de links sem cliques, senão desativa —
   KISS: DELETE desativa e mantém trilha), `POST /{id}/activate|deactivate`.
   Validações: `destination_url` http(s) SSRF-safe (reuso `WebhookUrlValidator`
   Spec 35/21), `slug` gerado único (6-8 chars), domínio pertence ao lojista.
3. **CRUD domínios**: `GET/POST/DELETE /portal/links/domains` — valida o
   domínio (formato + não-blocklist), no máx. 5 ativos (409 `too_many_domains`).
4. **CRUD sale_codes**: `GET/POST/PATCH/DELETE /portal/links/sale-codes` —
   `code` único por lojista, label, `affiliateId` (Spec 111).
5. **Stats por link**: `GET /portal/links/{id}/stats` → `{clicks, sales,
   revenue, conversionRate}` — cliques = COUNT `traffic_clicks`; sales = COUNT
   `pix_transactions` APPROVED com `sale_code` do link (coluna nova? —
   **decisão**: gravar `sale_code` na transação V62 — `ALTER pix_transactions
   ADD COLUMN sale_code` — para atribuição; revenue = Σ; conversão = sales ÷
   cliques × 100).
6. **Redirect `GET /l/{slug}`** (público): 301/302 para `destination_url` (ou
   `intermediate_page` quando configurado) com **filtro de tráfego**:
   - Decisão do filtro de tráfego server-side: combinação de `User-Agent` (bot das redes
     sociais — regex), `Sec-Fetch-*`, comportamento de bot (sem JS? decisão:
     não usa JS check na v1 — server-side UA + IP + ref), e flag de teste
     (`?preview=1`). Robô → **página limpa** (HTML estático "domínio" —
     ex.: o próprio domínio com um conteúdo neutro); humano → destino.
   - Conta o clique (INSERT `traffic_clicks` com `is_bot`).
   - Rate limit por IP no `/l/**` (Spec 43/59 — teto generoso).
7. **Preparar campanha**: `POST /portal/links/prepare-campaign` `{domainId,
   destinationUrl, utmSource, utmMedium, utmCampaign, ...}` → valida UTMs e
   monta/retorna a URL camuflada final (ou cria um link com as UTMs — decisão:
   valida e retorna a URL pronta para subir no anúncio).
8. **Segurança**: sem PII em logs (UA/IP não logados em claro — hash);
   `sale_code` nunca exposto em respostas públicas do `/l/`; auditoria Spec 41;
   SSRF-safe no destino.
9. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 35** (webhooks) — `WebhookUrlValidator` (SSRF-safe).
- **Spec 75** (funil) — atribuição da venda ao `sale_code`.
- **Spec 111** (afiliado) — `affiliate_id` em `sale_codes`.
- **Spec 41** (auditoria) — `PlatformAction.LINK_*`.
- **Spec 43/59** (rate limit) — `/l/**` por IP.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — o link → checkout
  gera cobrança sob o **perfil do contexto** (Spec 124: `profile_id` V70 +
  rota por perfil Spec 122/139); a métrica de vendas do link respeita o
  `X-Profile-ID`/lente ativa.

## Fora de escopo

- **UI dos links** — Spec 110.
- **Remarketing/redirecionadores** — Spec 117 (reuso desta infra).
- **JS-check/emulação avançada de filtro de tráfego** — v1 é server-side (UA+IP+ref);
  evolução.
- **Evasão de revisão de anúncio** — não faz parte (decisão do usuário:
  controle de tráfego documentado).
