---
name: Hardening financeiro e de contrato — auditoria QA 2026-08-04
status: audited
references:
  - "management/specs/14-withdrawals/requirements.md"
  - "management/specs/16-antifraud-compliance/requirements.md"
  - "management/specs/19-antifraud-real/requirements.md"
  - "management/specs/20-portal-foundation/requirements.md"
  - "management/specs/23-pix-charge-generator/requirements.md"
---

# Spec 26 — Hardening financeiro e de contrato (auditoria QA)

## Contexto

Auditoria QA completa do backend (2026-08-04) sobre specs já `audited` (14, 16,
19, 20) e `verified` (23) encontrou **3 riscos de integridade financeira**
(1 crítico) e **1 pipeline de CI quebrado**, além de gaps de contrato e de
cobertura de teste. Por tocarem specs concluídas, as correções são registradas
como **spec nova** (regra SDD), não como retrabalho.

## O que entrega

### C1 — Concorrência no wallet (crítico)
Sem `@Version`/`@Lock`, webhooks `PAID` concorrentes da mesma cobrança podiam
**creditar o wallet duas vezes** e saques concorrentes podiam **aprovar sobre o
mesmo saldo** (overdraft). Correção com **locks pessimistas** (`SELECT … FOR
UPDATE`) — sem migration:

- `MerchantJpaRepository.findByIdForUpdate` e
  `PixTransactionJpaRepository.findByExternalReferenceForUpdate`
  (`@Lock(PESSIMISTIC_WRITE)`); ports `findByIdForUpdate` /
  `findByExternalReferenceForUpdate` + adapters.
- Webhook (`ProcessPixWebhookService`), saque (`WithdrawalService`) e estorno
  (`RegisterChargebackService`) leem com lock → serializa o fluxo por
  transação/lojista.

### H1 — Estorno financeiro (refund/chargeback)
Antes, `REFUNDED` só mudava o status e chargeback não tocava o saldo → **o
lojista podia sacar dinheiro já devolvido ao comprador**. Agora:

- `Money` aceita valor negativo (saldo devedor do lojista quando o estorno
  chega após o saque — decisão de produto).
- `Merchant.debit()` — estorno sem as validações de saque.
- Webhook `REFUNDED` **debita o wallet** (idempotente: replay é no-op).
- `RegisterChargebackService` exige transação `APPROVED` (sem crédito → sem
  estorno) e **debita o wallet** na mesma transação.

### H2 — CI E2E consertado
O health check usava `curl` sem autenticação em `/api/v1/admin/**` (exige
`ROLE_ADMIN` desde a Spec 12) → 401 → job sempre falhava. Agora envia
`X-Api-Key: dev-only-admin-api-key` (default de dev) no health check.

### H3 — `expirationSeconds` propagado (contrato)
O parâmetro era aceito nas APIs (`/nyx/pix/cob` e `/portal/charges`) mas
**ignorado** — o PSP usava sempre o default configurado. Agora flui até
`payment.pix.expires_in` do Pagar.me (comando do port ganhou o campo;
`expirationSeconds` do cliente prevalece; null → default). Valores ≤ 0 → 400.

### M1/M6 — Teto de valor em todos os fluxos
A validação `amount ≤ charge-max-amount` (10.000,00) só existia no portal.
Agora está no `CreatePixChargeService` → vale também para `/nyx/pix/cob`
(bot) e para o **valor do preset** de Gift PIX.

### M2 — Timeouts no adapter Pagar.me
`SimpleClientHttpRequestFactory` com connect 5s / read 10s — antes uma
lentidão do PSP segurava a thread indefinidamente.

### M4 — Cobertura REST da Spec 23
Novo `MerchantChargeControllerTest` (8 testes) — a camada REST do gerador de
cobrança não tinha nenhum teste direto (a Spec 23 previa e não entregou).

### M5 — Cadastro concorrente → 409
Dois `POST /api/v1/merchants` com o mesmo documento em paralelo → violação do
índice único `document_hash` → 500. Agora `DataIntegrityViolationException` é
convertida em `MerchantAlreadyExistsException` → **409**.

### L1/L4 — Polimento de segurança/contrato
- **L1:** comparação da chave admin em tempo constante (`MessageDigest.isEqual`).
- **L4:** gift `presetId` **e** `amount` juntos → **400** (contrato é XOR).

## Critérios de aceite

1. `mvn test` verde — **175 testes, 0 falhas, 0 erros** (157 → 175; +18).
2. E2E Playwright **8/8** — novo cenário 8: login no portal, `/portal/me`,
   cobrança avulsa, catálogo de Gift PIX (5/10/20/50), cobrança por preset,
   preset inativo → 409, logout revoga → 401.
3. Webhook `REFUNDED` debita o wallet (saldo pode ficar negativo) — testado.
4. Chargeback em transação não-`APPROVED` → 409; chargeback debita o wallet.
5. `/nyx/pix/cob` com valor acima do teto → 400 `invalid_argument`
   (verificado via API real).
6. `expirationSeconds` chega ao `payment.pix.expires_in` do Pagar.me
   (testado com MockWebServer); `expirationSeconds ≤ 0` → 400.
7. CI: health check autenticado (job E2E volta a passar no push).
8. Timeouts 5s/10s no adapter Pagar.me.
9. Chave admin comparada em tempo constante.
10. Gift `presetId`+`amount` juntos → 400 `invalid_argument`.

## Dependências

- Spec 14 (wallet/saques — locks pessimistas).
- Spec 19 (webhook/chargeback — estorno financeiro).
- Spec 20 (portal — cenário E2E novo).
- Spec 23 (gerador de cobrança — contrato de `expirationSeconds`, teto, XOR).

## Decisões

- **Saldo negativo permitido** no estorno (lojista deve à plataforma quando já
  sacou) — a alternativa (bloquear estorno sem saldo) deixaria o furo original.
- **Locks pessimistas em vez de `@Version`**: os mappers recriam a entidade a
  cada `save()` — `@Version` exigiria propagar a versão pelo domínio; lock não
  precisa de migration e serializa corretamente os fluxos de dinheiro.
- **Fora de escopo (spec futura):** L2 (rate limit distribuído/Redis) e L3
  (prefixo de token do portal `ptl_`).

## Observações

- Código + testes entregues e verificados na sessão de auditoria; merge/commit e
  `/spec-audit` pendentes.
- Sem migration: C1 usa lock de banco; H1 usa coluna `wallet_balance` existente
  (`NUMERIC(15,2)` já aceita negativo).
- **Auditoria 2026-08-04: 10/10 critérios OK** — `mvn test` verde (175 testes:
  157 + 18); E2E Playwright **8/8** (cenário 8 do portal: login, /portal/me,
  cobrança avulsa, gift presets, logout); webhook `REFUNDED` debita o wallet
  (saldo -150,00 testado) e replay idempotente; chargeback em não-`APPROVED` →
  409 e chargeback debita o wallet; `/nyx/pix/cob` acima do teto → 400
  `invalid_argument` (curl real); `expirationSeconds ≤ 0` → 400 e o valor
  chega ao `expires_in` do Pagar.me (MockWebServer); CI health check
  autenticado (curl real 200 com `X-Api-Key` de dev); timeouts 5s/10s no
  adapter; chave admin em tempo constante (`MessageDigest.isEqual`); gift
  `presetId`+`amount` → 400. Commit `047fa62`.
