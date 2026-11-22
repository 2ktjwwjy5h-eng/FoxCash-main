---
name: Afiliado/Comissões — co-produtores e divisão de lucros (backend)
status: verified
---

# Spec 111 — Task: Afiliado/Comissões (backend)

## F0 — Migration + domínio

- [ ] **T0.1** — Migration **V63** (D2): `affiliates` + `affiliate_commissions`
      (+ `sale_codes.affiliate_id` já criado na Spec 109).
- [ ] **T0.2** — Entidades JPA + mappers + portas (`AffiliateRepository`,
      `AffiliateCommissionRepository`).
- [ ] **T0.3** — Ledgers: `WalletLedgerEntryType`/`MasterLedgerEntryType` +=
      `AFFILIATE_COMMISSION`, `AFFILIATE_COMMISSION_REVERSAL`.

## F1 — Service

- [ ] **T1.1** — `AffiliateService`: CRUD coprodutores (code `af_<8hex>`,
      % 1–50, 404 cross-lojista, DELETE desativa), `summary(merchantId)`.
- [ ] **T1.2** — `AffiliateCommissionService`: `calculate(tx, affiliate)` no
      PAID (D3 — idempotente via UNIQUE), `reverse(tx)` no estorno (D4).

## F2 — Hook no fluxo PAID

- [ ] **T2.1** — `ProcessPixWebhookService` (Spec 26/29): injeta o cálculo
      quando `tx.saleCode` tem afiliado ativo (na mesma transação).
- [ ] **T2.2** — Fluxo de estorno (Spec 29): reverte a comissão.

## F3 — Controller

- [ ] **T3.1** — `PortalAffiliateController` (`/api/v1/portal/affiliates`,
      `/affiliates/{id}/commissions`, `/affiliate/summary`), ROLE_MERCHANT_PORTAL.
- [ ] **T3.2** — `GlobalExceptionHandler` += 404 `affiliate_not_found`, 409
      `affiliate_already_exists` (UNIQUE merchant+affiliate).

## F4 — Testes + E2E

- [ ] **T4.1** — Unitários/integração (D7) + `mvn test` verde.
- [ ] **T4.2** — E2E `portal-affiliate.spec.js` (D7) + roadmap/status.

## Verificação (`/spec-verify 111`)

- [ ] Checar âncoras (`ProcessPixWebhookService`, `WalletLedgerRecorder`,
      `MasterLedgerRecorder`, `SaleCodeRepository`, ledgers) — sem achados.
