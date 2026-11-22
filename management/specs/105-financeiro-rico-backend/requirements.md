---
name: Financeiro rico — resumo de Pix, transações com gateway e resumo do período (backend)
status: verified
references:
  - "management/vision.md#V79"
  - "management/specs/22-merchant-wallet/requirements.md"
  - "management/specs/25-merchant-sales/requirements.md"
  - "management/specs/91-fallback-adquirente/requirements.md"
  - "management/specs/88-withdrawal-fee/requirements.md"
  - "management/specs/97-painel-usuarios-leitura/requirements.md"
---

# Spec 105 — Financeiro rico: resumo de Pix, transações com gateway e resumo do período (backend)

## O que entrega

Backend **aditivo read-only** para o **módulo Financeiro** do portal (Specs
22/25 — hoje wallet/sales separados e básicos) com a descrição de produto do
usuário (2026-08-15).

1. **Filtros temporais**: `period = hoje|ontem|semana|mes|todo` em todos os
   endpoints do módulo.
2. **Resumo de Pix** (`GET /api/v1/portal/financial/summary?period=`):
   `{pixGenerated, pixPaid, pixPending, grossVolume, methodsPixPercent}` —
   contadores de Pix Gerados/Pagos/Pendentes e o gráfico de métodos (Pix % do
   faturamento).
3. **Resumo do período** (mesmo endpoint): `{averageTicket, expiredCount,
   refundCount, uniqueLeads}` — Ticket Médio, Expirados, Reembolsos, Leads
   Únicos.
4. **Tabela de transações** (`GET /api/v1/portal/transactions?period=&gateway=
   &status=&flow=`): `{transactionId, customerName (lead Spec 97 D5) |
   buyerIdHash, productName (plano), gateway (provider Spec 91), status, flow
   (compra/estorno), amount, netAmount, createdAt}` — com filtros por Gateway,
   Status e Fluxo e valores bruto/líquido.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — módulo Financeiro do portal de
referência. Lacuna: `WalletPage`/`SalesPage` não têm filtros temporais, resumo
de Pix, gateway na tabela nem resumo do período. Backend dos dados existe
(Specs 22/25/91/88).

## Critérios de aceite

1. **`GET /api/v1/portal/financial/summary?period=`** → `{pixGenerated,
   pixPaid, pixPending, grossVolume, methodsPixPercent, averageTicket,
   expiredCount, refundCount, uniqueLeads}`. Períodos: `hoje|ontem|semana|
   mes|todo` (UTC; `semana` = últimos 7 dias — decisão: última segunda a hoje,
   documentada). `pixGenerated` = COUNT criados; `pixPaid` = APPROVED;
   `pixPending` = PENDING (Spec 25). `expiredCount` = status EXPIRED/REJECTED
   (mapping real — Spec 23/28: `expired`); `refundCount` = REFUNDED/chargeback
   (Spec 29). `uniqueLeads` = COUNT `bot_leads` únicos na janela (Spec 83).
2. **`GET /api/v1/portal/transactions`** → `PageResponse` com `gateway`
   (`provider` da Spec 91; null → "PADRAO"), `status`, `flow`
   (`SALE`/`REFUND`/`CHARGEBACK`), `customerName` (via lead Spec 97 D5 —
   reuso da lógica, sem PII nova), `productName` (plano Spec 49), `amount`,
   `netAmount` (bruto − taxa — `PricingCalculator` Spec 25/98), `createdAt`.
   Filtros: `period`, `gateway`, `status`, `flow`, `page`/`size` (cap 100).
3. **Escopo por lojista** (principal — IDOR-safe, padrão Spec 20).
4. **Sem PII em logs** (nome do cliente mascarado).
5. **Segurança/rate limit**: `/api/v1/portal/**` já coberto (Spec 58).
6. **Contratos existentes inalterados**: `/portal/wallet/*`, `/portal/sales/*`
   intactos — endpoints aditivos.
7. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 22/25** (wallet/sales) — base de dados e filtros.
- **Spec 91** (routing) — `provider` nas transações.
- **Spec 97** (painel master) — lógica de `customerName`/`productName`/`net`
  (reuso — porta/helper extraída).
- **Spec 88** (taxa de saque) — taxas (não muda).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — as transações têm
  `profile_id` (V70) + `provider` (V58); o resumo e a tabela são **escopados
  pelo perfil ativo** (Spec 124, `X-Profile-ID`) — respeitar a lente
  Bot/Gateway ao filtrar.

## Fora de escopo

- **UI do Financeiro** — Spec 106.
- **Exportação/PDF** — evolução.
- **Gráfico de métodos com mais de 1 método** — o backend só tem Pix; o gráfico
  mostra Pix 100% ou a divisão Pix/estornos (decisão na UI — Spec 106).
