---
name: Gerador de cobrança PIX — cobrança avulsa + Gift PIX (valores predefinidos)
status: audited
references:
  - "management/vision.md#V17"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/12-api-authentication/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
---

# Spec 23 — Gerador de cobrança PIX (backend-first)

## O que entrega

APIs do **Gerador de Cobrança PIX** do portal do lojista — dois fluxos de
criação de cobrança sobre o endpoint existente (`POST /api/v1/foxcash/pix/cob`,
Spec 19):

1. **Cobrança avulsa:** formulário com valor, descrição e opções (tempo de
   expiração) → gera o PIX. Reuso do fluxo existente, agora autenticado pela
   sessão do portal (`ROLE_MERCHANT_PORTAL`), sem API key.
2. **Gift PIX (valores predefinidos):** catálogo de valores fixos configuráveis
   por lojista (ex.: R$ 5, R$ 10, R$ 20, R$ 50) + opção **personalizado** —
   gera cobrança com um clique sobre o valor selecionado.
3. **Catálogo de gifts:** `GET /api/v1/portal/charges/gift-presets` → lista de
   `{amount, label, active}`; o lojista pode ativar/desativar presets
   (`PUT /api/v1/portal/charges/gift-presets/{id}`) — configuração persistida
   em tabela nova (migration V13 junto com a Spec 20).

**Decisão (confirmada 2026-08-04):** "Gift PIX" = **valores predefinidos que
o bot do lojista oferece ao cliente** (compra rápida sem digitar valor), com
opção personalizado. O catálogo de presets é configurável pelo lojista
(ativar/desativar); defaults R$ 5/10/20/50.

## Critérios de aceite

1. `POST /api/v1/portal/charges` `{amount, description, expirationSeconds,
   buyerId?}` → 201 com `{transactionId, pixQrCode, pixCopyPaste, status,
   externalReference}` — mesmo contrato do `/nyx/pix/cob` (Spec 19), com
   triagem antifraude ativa; autenticado pela sessão do portal.
2. `GET /api/v1/portal/charges/gift-presets` → lista `{id, amount, label,
   active}` (defaults: R$ 5/10/20/50 ativos); exige `ROLE_MERCHANT_PORTAL`.
3. `PUT /api/v1/portal/charges/gift-presets/{id}` `{active}` → 204 e persiste;
   preset inexistente → `404 gift_preset_not_found`.
4. `POST /api/v1/portal/charges/gift` `{presetId | amount, description?,
   buyerId?}` → 201 gerando cobrança pelo preset selecionado (ou valor
   personalizado); preset inativo → `409 gift_preset_inactive`; buyerId com
   chargeback → `409 fraud_buyer_blocked` (Spec 19).
5. Valor inválido (≤ 0, > limite) → `400 invalid_argument`; `mvn test` verde
   (**~153 testes: 147 + ~6 novos** — `GiftPresetServiceTest`,
   `MerchantChargeControllerTest`) e E2E sem regressão.

## Dependências

- Spec 20 (auth do portal).
- Spec 19 (triagem antifraude na cobrança — `buyerId` opaco, hash SHA-256).
- Spec 12 (padrão `X-Api-Key` permanece para integrações; portal usa sessão).

## Observações

- **Reuso, não duplicação:** o `CreatePixChargeService` (Spec 19) é chamado
  com o principal do portal resolvido — sem novo caminho de cobrança.
- **`buyerId` segue opcional e pseudônimo** (sem PII; só hash persistido).
- **Frontend fica para spec futura** — o contrato REST é o entregável.
- **Auditoria 2026-08-04: 5/5 critérios OK** — `POST /api/v1/portal/charges`
  201 com contrato completo (E2E cenário 8); `GET gift-presets` 4 defaults R$
  5/10/20/50 ativos; `PUT gift-presets/{id}` 204 e inativo → 409
  `gift_preset_inactive` (404 `gift_preset_not_found` testado); `POST
  /charges/gift` por preset/personalizado com XOR validado (`presetId`+`amount`
  → 400); valor inválido/≥ teto → 400; `mvn test` verde (175: 153 + 22 novos
  entre Spec 23 e 26) e E2E **8/8** sem regressão. Implementada junto com a
  Spec 26 (mesmo commit `047fa62` — a QA incluiu a cobertura REST M4).
