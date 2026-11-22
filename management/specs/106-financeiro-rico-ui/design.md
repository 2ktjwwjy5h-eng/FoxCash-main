---
name: Financeiro rico — UI do portal
status: verified
---

# Spec 106 — Design: Financeiro rico (UI)

## D1 — Conceito

`FinancialPage.tsx` nova (rota `/financial`) compondo os endpoints da Spec 105
com filtros temporais em estado compartilhado. Reuso de componentes (Spec 73).

```text
portal/src/pages/FinancialPage.tsx   → nova página
portal/src/components/PeriodTabs.tsx → segments Hoje/Ontem/Semana/Mês/Tudo
portal/src/components/PixSummaryCards.tsx
portal/src/components/MethodChart.tsx → barra de participação
portal/src/components/FinancialTable.tsx → tabela com filtros gateway/status/flow
portal/src/components/PeriodSummary.tsx → lateral (ticket/expirados/reembolsos/leads)
```

## D2 — Estado

`period` (estado) → `useAsync` dispara `summary` e `transactions` com
`?period=`. Filtros da tabela (gateway/status/flow) → query params.

## D3 — Layout

`[PeriodTabs] / [PixSummary 3 cards + grossVolume] / [MethodChart | PeriodSummary
lateral] / [FinancialTable]`.

## D4 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-financial-ui.spec.js`: troca de período recarrega, cards corretos,
  tabela com gateway (PADRAO) e líquido, resumo lateral, empty state.

## D5 — Wireframe aprofundado (registro de design 2026-08-16)

Visual de referência completo da página **Financeiro** (`/financial`).

```
┌────────────────────────────────────────────────────────────────────────┐
│  Financeiro                                  [Hoje][Ontem][Semana][Mês][Tudo]│
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐   Volume bruto    │
│  │ Pix Gerados  │ │ Pix Pagos    │ │ Pendentes    │   R$ 3.867,00     │
│  │   1.234      │ │   1.192      │ │      42      │   (período)       │
│  └──────────────┘ └──────────────┘ └──────────────┘                   │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐   │
│  │  Métodos de pagamento      │  │  Resumo do período            │   │
│  │  Pix  ████████████████████ 100%│  │  Ticket médio   R$ 89,90     │   │
│  │  (só Pix existe — mostra     │  │  Expirados      12            │   │
│  │   "Pix 100%" com volume)     │  │  Reembolsos     R$ 120,00     │   │
│  └──────────────────────────────┘  │  Leads únicos   458           │   │
│                                    └──────────────────────────────┘   │
│  ── Transações ──                                                    │
│  [Gateway: Todas ▾] [Status: Todos ▾] [Fluxo: Todos ▾]               │
│  ┌────────────┬───────────┬─────────┬────────┬─────────┬─────────┬──┐│
│  │ Cliente    │ Produto   │ Gateway │ Status │ Data    │ Bruto   │Líq││
│  ├────────────┼───────────┼─────────┼────────┼─────────┼─────────┼──┤│
│  │ @fulano    │ PREMIUM   │ PADRAO  │[PAGO]  │16/08 02│R$ 49,90 │...││
│  │ buyerId..a│ —         │ Asaas   │[PEND]  │16/08 01│R$ 29,90 │...││
│  │ @ciclana   │ BÁSICO    │ PADRAO  │[EXP]   │15/08 23│R$ 9,90  │...││
│  │ ...        │           │         │        │        │         │   ││
│  └────────────┴───────────┴─────────┴────────┴─────────┴─────────┴──┘│
│  [← Anterior]  Página 1 de 9  [Próxima →]                            │
└────────────────────────────────────────────────────────────────────────┘
```

### D5.1 — Estados

- **Loading inicial**: `Spinner` central (Spec 73) — sem "blink"; ao trocar o
  período, `useAsync` mantém os dados anteriores visíveis (padrão já usado em
  BotsPage).
- **Erro**: `ErrorBanner` no topo com `reload()`; os cards/resumo mantêm o
  estado anterior (não resetam para vazio).
- **Empty**: período sem dados → cards zerados (`R$ 0,00` / `0`), gráfico mostra
  "Sem dados no período", resumo lateral com `—`, tabela com empty state
  "Nenhuma transação no período." + CTA "Limpar filtros".
- **Tabela vazia com filtro**: "Nenhuma transação para os filtros
  selecionados." + botão "Limpar filtros" (reseta gateway/status/fluxo).

### D5.2 — Interações

- **PeriodTabs**: estado local `period` → `useAsync` recarrega `summary` +
  `transactions` com `?period=`. Tab ativa com `aria-pressed` (padrão BotsPage).
- **Filtros da tabela**: select Gateway/Status/Fluxo → query params; qualquer
  mudança reseta a paginação para a página 1.
- **Linha clicável**: abre o detalhe (modal simples — reuso do padrão de
  transação do console; fora do escopo: detalhe completo = Spec 125).
- **Ordenação**: clicar no cabeçalho de Data/Bruto/Líquido alterna
  asc/desc (client-side na página atual; ordenação server-side = evolução).

### D5.3 — Formatos e colunas

- Cliente: nome do lead (`bot_leads`) ou `buyerIdHash` truncado
  (`abc123…9f`) com `title` (tooltip) do hash completo.
- Produto: `productName` da transação ou `—`.
- Gateway: `PADRAO` (routing default) ou nome do provider real (Spec 91);
  badge de cor neutra.
- Status: badge — `PAGO` (tag-success), `PENDENTE` (tag-warning), `EXPIRADO`
  (tag-neutral), `REEMBOLSADO` (tag-danger) — mapeamento de `TransactionStatus`.
- Bruto/Líquido: `formatMoney`; líquido = bruto − taxas (Spec 105 já devolve).
- **Paginação**: 20 linhas/página; rodapé `[← Anterior] Página X de Y
  [Próxima →]`; botões desabilitados nas bordas.
