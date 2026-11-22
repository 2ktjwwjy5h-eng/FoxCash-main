---
name: Afiliado/Comissões — co-produtores e divisão de lucros (backend)
status: verified
---

# Spec 111 — Design: Afiliado/Comissões (backend)

## D1 — Conceito

Um hook **`AffiliateCommissionService`** injetado no fluxo PAID (Spec 26/29) +
CRUD de coprodutores. Comissão idempotente por transação (UNIQUE
`transaction_id`).

```text
PortalAffiliateController (ROLE_MERCHANT_PORTAL) — CRUD + summary
   └─ AffiliateService / AffiliateCommissionService
        ├─ SaleCodeRepository (Spec 109) — resolução por sale_code
        ├─ AffiliateRepository — coprodutor + %
        ├─ WalletLedgerRecorder (Spec 27) — AFFILIATE_COMMISSION
        └─ MasterLedgerRecorder (Spec 34) — AFFILIATE_COMMISSION
```

## D2 — Migration V63

```sql
CREATE TABLE affiliates (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  affiliate_id UUID NOT NULL REFERENCES merchants(id), code VARCHAR(32) NOT NULL UNIQUE,
  commission_percent NUMERIC(5,2) NOT NULL CHECK (commission_percent BETWEEN 1 AND 50),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (merchant_id, affiliate_id));
CREATE TABLE affiliate_commissions (id UUID PK, transaction_id UUID NOT NULL REFERENCES pix_transactions(id) UNIQUE,
  affiliate_id UUID NOT NULL, merchant_id UUID NOT NULL,
  commission_percent NUMERIC(5,2) NOT NULL, amount NUMERIC(15,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at TIMESTAMPTZ NOT NULL);
CREATE INDEX ix_affiliate_commissions_affiliate ON affiliate_commissions (affiliate_id, status);
```

**V63** (V62 = Spec 109).

## D3 — Hook no PAID

No `ProcessPixWebhookService` (Spec 26/29), quando `transaction.approve()`:
se `transaction.saleCode` presente → `saleCode.affiliate` ativo →
`AffiliateCommissionService.calculate(transaction, affiliate)` na **mesma
transação**:

- INSERT `affiliate_commissions` (PENDING, `amount = amount × %`).
- Credita wallet do afiliado (`WalletLedgerEntryType.AFFILIATE_COMMISSION`) com
  `balance_after` (Spec 27).
- Master ledger `MasterLedgerEntryType.AFFILIATE_COMMISSION` (Spec 34).
- UNIQUE `transaction_id` garante idempotência no replay PAID (Spec 26) — o
  catch de `DataIntegrityViolationException` → no-op (padrão Spec 29).

## D4 — Estorno/chargeback

No fluxo de estorno (Spec 29): se há comissão PENDING → REVERSED + débito do
wallet do afiliado (`AFFILIATE_COMMISSION_REVERSAL`) + reversão no master
(`AFFILIATE_COMMISSION_REVERSAL`). Idempotente (gate de status).

## D5 — Endpoints

- `GET/POST/PATCH/DELETE /api/v1/portal/affiliates` — CRUD (POST gera `code`
  `af_<8hex>`; PATCH parcial; DELETE desativa).
- `GET /api/v1/portal/affiliates/{id}/commissions` — histórico paginado.
- `GET /api/v1/portal/affiliate/summary` — `{paidTotal, pendingTotal,
  attributedSales, attributedRevenue}`.

## D6 — Ledgers

`WalletLedgerEntryType` += `AFFILIATE_COMMISSION`, `AFFILIATE_COMMISSION_REVERSAL`;
`MasterLedgerEntryType` += `AFFILIATE_COMMISSION`, `AFFILIATE_COMMISSION_REVERSAL`
(VARCHAR(30) — Spec 52 OK).

## D7 — Testes

**Unitários:** `AffiliateServiceTest` (CRUD, % 1–50, code único, 404
cross-lojista), `AffiliateCommissionServiceTest` (cálculo, idempotência no
replay, estorno revoga, sem afiliado → no-op). **Integração:** controllers.
**E2E** (`portal-affiliate.spec.js`): criar coprodutor → venda com `sale_code`
→ comissão no wallet do afiliado + ledger + summary; estorno → REVERSED.
