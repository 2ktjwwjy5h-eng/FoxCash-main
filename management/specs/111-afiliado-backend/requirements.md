---
name: Afiliado/Comissões — co-produtores e divisão de lucros (backend)
status: verified
references:
  - "management/vision.md#V82"
  - "management/specs/109-links-filtro de tráfego-backend/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/29-consistent-refund/requirements.md"
---

# Spec 111 — Afiliado/Comissões: co-produtores e divisão de lucros (backend)

## O que entrega

Backend do módulo **Afiliado** do portal (descrição de produto do usuário
2026-08-15): gestão de **co-produtores e divisão de lucros** — comissões por
`sale_code`/link, calculadas na venda PAID e pagas ao wallet do afiliado.

1. **`affiliates`** (V63): `id, merchant_id (dono), affiliate_id (lojista
   afiliado/coprodutor — FK merchants), code (código do afiliado), commission_percent,
   status (ACTIVE/INACTIVE), created_at`. (O `sale_codes.affiliate_id` da Spec
   109 aponta para esta tabela.)
2. **`affiliate_commissions`** (V63): `id, transaction_id, affiliate_id,
   merchant_id (dono), commission_percent, amount, status (PENDING/PAID/
   REVERSED), created_at` — idempotente por `transaction_id`.
3. **Cálculo na venda**: no webhook PAID (Spec 26/29), se a transação tem
   `sale_code` com afiliado ativo → calcula `amount × commission_percent/100`
   na mesma transação, registra `affiliate_commissions` (PENDING) e credita o
   wallet do afiliado (`WalletLedgerEntryType.AFFILIATE_COMMISSION`) +
   ledger do master (`MasterLedgerEntryType.AFFILIATE_COMMISSION`).
4. **Estorno/chargeback**: venda estornada/chargeback revoga a comissão
   (PENDING → REVERSED; débito do wallet do afiliado, mesmo fluxo do estorno
   Spec 29 — sem dupla contagem Spec 30).
5. **Endpoints do portal** (ROLE_MERCHANT_PORTAL):
   - `GET/POST/PATCH/DELETE /api/v1/portal/affiliates` (CRUD coprodutores;
     `code` gerado, `commission_percent` 1–50).
   - `GET /api/v1/portal/affiliates/{id}/commissions` (histórico).
   - `GET /api/v1/portal/affiliate/summary` (total de comissões pagas/
     pendentes + leads/vendas atribuídas).
   - **Retirada**: o afiliado é um lojista (wallet próprio) — a comissão PAID
     entra no wallet dele; retirada = saque normal (Spec 14/32).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Afiliado do portal de
referência: gestão de co-produtores e divisão de lucros. O `sale_code` da Spec
109 (criada junto) é a base da atribuição. Backend financeiro já tem ledger
(Specs 27/34) e estorno consistente (Spec 29).

## Critérios de aceite

1. **Migration V63**: `affiliates` + `affiliate_commissions` — FKs, UNIQUE
   `(merchant_id, affiliate_id)`, índice `affiliate_commissions(affiliate_id,
   status)`. **V63** (V62 = Spec 109).
2. **CRUD coprodutores**: `POST /affiliates` (201, `code` gerado único,
   percentual 1–50 — 400 fora), `GET` (lista com vendas atribuídas), `PATCH`,
   `DELETE` (desativa — preserva histórico). 404 cross-lojista.
3. **Cálculo na venda**: webhook PAID com `sale_code` → `sale_codes.
   affiliate_id` → afiliado ACTIVE → comissão `amount × %` (round 2 casas),
   PENDING + `AFFILIATE_COMMISSION` no wallet do afiliado + master ledger, na
   mesma transação. Replay PAID (Spec 26) → idempotente (1 comissão).
4. **Estorno/chargeback**: revoga a comissão (REVERSED) + débito do wallet do
   afiliado + reversão no master (padrão `WITHDRAWAL_FEE_REVERSAL`/SEC-29).
5. **Resumo**: `GET /affiliate/summary` → `{paidTotal, pendingTotal,
   attributedSales, attributedRevenue}`.
6. **Sem dupla contagem**: comissão é única por transação (UNIQUE
   `transaction_id`); reconciliação da Spec 30 não quebra.
7. **Segurança**: escopo por lojista (IDOR-safe); sem PII em logs; rate limit
   `/api/v1/portal/**` (Spec 58).
8. **Contratos existentes inalterados** (Specs 26/27/29/34 intactas — o
   cálculo é um hook no fluxo PAID).
9. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 109** — `sale_codes` + `sale_code` na transação.
- **Spec 26/29** — fluxo PAID/estorno (onde injeta o cálculo).
- **Spec 27/34** — ledgers (wallet + master).
- **Spec 14/32** — retirada via saque do afiliado.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — a comissão é
  atribuída na cobrança PAID, que tem `profile_id` (V70, Spec 124); sem mudança
  de contrato — mas o resumo por afiliado/link respeita o perfil do contexto.

## Fora de escopo

- **UI do Afiliado** — Spec 112.
- **Payout automático de comissão** — a comissão cai no wallet; retirada é
  saque normal (nada novo).
- **Comissão por níveis (multi-nível)** — evolução.
