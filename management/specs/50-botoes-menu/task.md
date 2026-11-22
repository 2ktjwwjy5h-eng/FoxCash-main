# Spec 50 — Task breakdown

Status: `audited` (2026-08-06). **Ciclo SDD completo — feature LIVE.**

## Auditoria (`/spec-audit`, 2026-08-06)

- [x] **16/16 checks ao vivo OK** contra o app dev (loja smoke com API key
      real): CRUD completo, `PUT /order` 0..N, validações 400, **limite 20 →
      409 `too_many_buttons`**, storefront `buttons[]` ativos ordenados, 404
      inexistente/cross-loja.
- [x] Auditoria Spec 41 (`BUTTON_CREATED/UPDATED/DELETED` na mesma transação)
      confirmada nos testes unitários. `mvn test` 675/0/0 e E2E 25/25 sem
      regressão. **0 achados.**

## Implementação

- [x] Migration **V39** `store_buttons` (a V38 foi consumida pela Spec 49 —
      `V38__delivery_evidence.sql`; mesmo padrão documentado) com unique
      `(store_id, position)` + índice `ix_store_buttons_store`.
- [x] `StoreButton`/`StoreButtonAction` (validação por tipo na aplicação) +
      `StoreButtonRepository`.
- [x] `StoreButtonPayloadCodec` — payload validado por tipo (BUY/CLOSE sem
      payload; PREVIEW `mediaUrls[]`; FAQ `text`; SOCIAL/LINK `url,label?`),
      serializado em JSON e **criptografado em repouso** (CryptoConverter).
- [x] `StoreButtonService` + `PortalStoreButtonController`
      (`GET/POST /api/v1/portal/store/buttons`, `PATCH/DELETE /{id}`,
      `PUT /order`; limite 20 → 409 `too_many_buttons`; reordenação recalcula
      positions 0..N; DELETE reindexa posições).
- [x] `StorefrontService` (Spec 48) inclui `buttons[]` ativos ordenados
      (`findActiveByStoreIdOrderByPosition`) — `StorefrontResponse` expõe
      `{id, label, action, payload, position}` (payload em claro ao dono via
      API key).
- [x] `PlatformAction` += BUTTON_CREATED/UPDATED/DELETED; trilha sem payload
      (só label/action); handlers 404 `button_not_found` / 409
      `too_many_buttons` no `GlobalExceptionHandler`.
- [x] Testes: `StoreButtonTest` (9), `StoreButtonPayloadCodecTest` (7),
      `StoreButtonServiceTest` (13), `PortalStoreButtonControllerTest` (9),
      storefront com buttons (`StorefrontServiceTest`/`FoxStorefrontControllerTest`
      atualizados); E2E cenário **25** (menu customizado, reordenação,
      400/404/409, payload `v1:` no banco, auditoria).

## Verificação

- [x] `mvn test` verde (suíte completa sem regressão).
- [x] E2E **22/22 + cenário 25** sem regressão.

## Verificação (`/spec-verify`, 2026-08-05)

- [x] Consistência verificada: menu 100% configurável (tipos de ação + ordem);
      limite 20 botões; reordenação recalcula positions; storefront devolve
      botões ativos.
- [x] Achados aplicados: payload do botão **sempre criptografado** (design
      estava ambíguo — "quando contém link privado"). Status → `verified`.
