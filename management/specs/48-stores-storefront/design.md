# Spec 48 — Design: Stores e storefront (backend)

## Contexto

Os bots (Spec 52) precisam renderizar a loja sem hardcode. A loja (`stores`)
vira a **fonte de verdade** configurável pelo painel (portal). Modelo 1:1 com
merchant por decisão de produto, mas `stores.merchant_id` permite 1:N no futuro
sem redesenho.

## Decisões de arquitetura

### 1. Modelo (migration V36 — parte 1: stores)

- `stores`:
  - `id UUID PK`, `merchant_id UUID NOT NULL UNIQUE` (1:1 hoje; UNIQUE remove
    se virar 1:N), `name VARCHAR(120) NOT NULL`, `logo_url VARCHAR(500)`,
    `banner_url VARCHAR(500)`, `description VARCHAR(500)`,
    `social_links TEXT` (JSON array de `{label, url}` — máx. 10),
    `color VARCHAR(9)` (hex `#RRGGBB`), `emoji VARCHAR(10)`,
    `title VARCHAR(120)`, `footer VARCHAR(500)`, `created_at`, `updated_at`.
  - Nenhum dado de identidade é PII do comprador — sem criptografia em repouso
    (são dados públicos da loja). O que for sensível (links privados de acesso)
    vive no payload criptografado das ações de entrega (Spec 49).

### 2. Domínio e portas

- `Store` (domain/model/store): `create(merchantId, ...)` + `restore` +
  `update(...)` (validações: nome não-branco ≤120; URLs ≤500; cor `#RRGGBB`;
  `social_links` ≤10 itens com label ≤40 e url ≤500).
- Portas: `StoreRepository` — `save`, `findByMerchantId`,
  `findByIdAndMerchantId` (404 cross-loja), `existsByMerchantId`.

### 3. Serviços

- `StoreService` (portal, ROLE_MERCHANT_PORTAL):
  - `GET /api/v1/portal/store` → loja do principal (404 `store_not_found`).
  - `POST /api/v1/portal/store` (201) → 409 `store_already_exists` se já existe.
  - `PATCH /api/v1/portal/store` (200) — atualiza qualquer campo de identidade.
  - Tudo privado por lojista (principal == merchantId); 400 `invalid_argument`.
  - Atividade: `STORE_CREATED`, `STORE_UPDATED` (Spec 41).
- `StorefrontService` (nyx, API key do lojista):
  - `GET /api/v1/foxcash/storefront` → monta o DTO agregado:
    `{store, buttons[] (Spec 50, ordenados), plans[] (Spec 49, ativos),
    messages{} (Spec 51), deliveryConfig}` — 404 `store_not_found` se não há loja.
  - Não expõe segredos: o `delivery_payload` das ações só é devolvido na API de
    entrega do lojista dono (Spec 49), nunca no storefront público.

### 4. Storefront (contrato consumido pelo bot)

```json
{
  "store": { "name": "Bella Premium", "logoUrl": "...", "bannerUrl": "...",
             "description": "Conteúdo exclusivo", "socialLinks": [...],
             "color": "#9B59B6", "emoji": "⭐", "title": "...", "footer": "..." },
  "buttons": [ { "id": "u1", "label": "🛒 Comprar", "action": "buy", "position": 1 },
               { "id": "u2", "label": "📷 Ver prévia", "action": "preview", "position": 2 } ],
  "plans": [ { "id": "p1", "name": "VIP Bronze", "price": 39.90, "imageUrl": "...",
               "embedColor": "#8E44AD", "featured": true, "description": "Fotos + vídeos" } ],
  "messages": { "welcome": "Olá 👋 Bem-vindo à Bella Premium.", "awaitingPayment": "...",
                "paymentReceived": "...", "delivered": "...", "promo": "..." }
}
```

O bot renderiza essa configuração (Spec 52) — fluxo 100% orientado por
configuração, sem atualizar/reiniciar os bots ao editar a loja.

### 5. Segurança / cross-cutting

- Acesso por recurso (principal == merchantId) em todas as rotas.
- Rate limit (Spec 43) nas rotas do storefront.
- Logs mascarados (Spec 41); validação de entrada (Bean Validation + domínio).
- `PlatformAction` += `STORE_CREATED`, `STORE_UPDATED`.

## Arquivos (visão geral)

- `domain/model/store/Store.java`, `domain/port/out/StoreRepository.java`
- `application/service/StoreService.java`, `StorefrontService.java`
- `infrastructure/outbound/persistence/entity/StoreJpaEntity.java` (+ mapper/adapter)
- `infrastructure/inbound/rest/PortalStoreController.java`,
  `FoxStorefrontController.java` + DTOs
- migration `V36` (parte 1: `stores`)
- Testes: domínio, serviços, controllers; E2E cenário novo
