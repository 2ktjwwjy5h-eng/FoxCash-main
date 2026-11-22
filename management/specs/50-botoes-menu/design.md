# Spec 50 — Design: Botões, menu e fluxo configurável

## Decisões de arquitetura

### 1. Modelo (migration V36 — parte 3: `store_buttons`)

- `store_buttons`: `id UUID PK`, `store_id UUID FK` (loja raiz; merchant via
  store), `label VARCHAR(80) NOT NULL`, `action VARCHAR(30) NOT NULL`
  (BUY/PREVIEW/FAQ/SOCIAL/LINK/CLOSE), `payload TEXT` (JSON — `{url,label?}`,
  `{mediaUrls[]}`, `{text}`; **sempre criptografado** via `CryptoConverter` —
  padrão do projeto; pode conter link privado/canal VIP), `position INT NOT
  NULL`, `active BOOLEAN DEFAULT TRUE`, `created_at`. Unique `(store_id,
  position)`.

### 2. Domínio

- `StoreButton`: create/restore/update/activate/deactivate; validações por
  `action` (label 1–80; payload obrigatório para PREVIEW/FAQ/SOCIAL/LINK e
  validado: `url`/`mediaUrls` ≤500, `text` ≤1000). Enum `StoreButtonAction`.

### 3. Serviço/controller (portal)

- `StoreButtonService` + `PortalStoreButtonController`:
  - `GET /api/v1/portal/store/buttons` (ordenado por position).
  - `POST .../buttons` (201, position = max+1) — 409 `too_many_buttons` > 20.
  - `PATCH .../buttons/{id}` (label/action/payload/active).
  - `DELETE .../buttons/{id}` (204 — remove e recalcula posições).
  - `PUT .../buttons/order` `{ids[]}` — reordena (valida que ids pertencem à
    loja; recalcula positions 0..N).
  - Todos privados por lojista (store→merchant; 404 cross-loja); 400
    `invalid_argument`; atividade `BUTTON_CREATED/UPDATED/DELETED`.

### 4. Storefront (Spec 48)

- `StorefrontService` inclui `buttons[]` ativos ordenados (DTO sem payload
  sensível em claro — apenas o necessário para renderizar: label/action; o
  payload de `link`/`social` é devolvido pois é do lojista dono via API key).

## Arquivos

- `domain/model/store/StoreButton.java` (+ `StoreButtonAction`),
  `domain/port/out/StoreButtonRepository.java`
- `application/service/StoreButtonService.java`
- `infrastructure/outbound/persistence/entity/StoreButtonJpaEntity.java` (+ mapper)
- `infrastructure/inbound/rest/PortalStoreButtonController.java` + DTOs
- `StorefrontService` (Spec 48) inclui buttons
- Testes: domínio, serviço (validações/limite/ordem), controller, E2E
