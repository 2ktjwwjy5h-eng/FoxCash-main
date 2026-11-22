---
name: Botões, menu e fluxo configurável da loja (backend)
status: audited
references:
  - "management/vision.md#V31"
  - "management/specs/48-stores-storefront/requirements.md"
---

# Spec 50 — Botões, menu e fluxo configurável da loja (backend)

## O que entrega

O **menu inicial** da loja vira **configuração** — o vendedor monta o fluxo no
painel e o bot apenas **renderiza** (Spec 52). Cada loja tem uma lista ordenada
de `store_buttons`:

- **Tipos de ação:**
  - `buy` — abrir a lista de planos (Spec 49). Payload: nenhum.
  - `preview` — abrir a prévia (mídias do plano/loja). Payload: `{mediaUrls[]}`.
  - `faq` — abrir as mensagens de dúvidas. Payload: `{text}`.
  - `social` — abrir link (Instagram, "Falar comigo"...). Payload: `{url, label?}`.
  - `link` — link externo qualquer (promoção, canal VIP). Payload: `{url, label?}`.
  - `close` — encerrar o menu. Payload: nenhum.
- O vendedor escolhe **quais botões e em que ordem** — nada é obrigatório; pode
  haver vários `social`/`link` (ex.: Instagram, canal VIP, promoção).
- **Prévia de plano:** o botão `preview` e/ou a ação de prévia por plano
  (Spec 49) expõem mídias que o vendedor configurou.

## Critérios de aceite

- **CRUD (portal):** `GET/POST /api/v1/portal/store/buttons` (cria com `position`
  no fim), `PATCH /{id}` (label/action/payload), `DELETE /{id}` (204),
  `PUT /api/v1/portal/store/buttons/order` `{ids[]}` (reordena; posições
  recalculadas 0..N). Validações: label 1–80, `action` enum, `payload` obrigatório
  conforme o tipo (URLs ≤500), máx. 20 botões → 409 `too_many_buttons`.
- **Storefront:** `GET /api/v1/foxcash/storefront` (Spec 48) devolve `buttons[]`
  ordenados (apenas os ativos). Bot renderiza o menu; tocar um botão navega
  pelo fluxo configurado.
- **Regressão:** `mvn test` verde; E2E **22/22 + cenário** (menu com botões
  customizados, reordenação, 400/404/409).
- **Cross-cutting:** validação, rate limit, logs mascarados, atividade
  `BUTTON_CREATED`/`BUTTON_UPDATED`/`BUTTON_DELETED` (Spec 41).

## Dependências

- `management/specs/48-stores-storefront/requirements.md` (loja raiz)
- `management/specs/49-planos-entregas/requirements.md` (botão `buy`/`preview`)
- `management/specs/41-master-profiles-activity/requirements.md` (auditoria)
