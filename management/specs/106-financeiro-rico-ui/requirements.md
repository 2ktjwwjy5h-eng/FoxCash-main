---
name: Financeiro rico — UI do portal
status: verified
references:
  - "management/vision.md#V79"
  - "management/specs/105-financeiro-rico-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/22-merchant-wallet/requirements.md"
---

# Spec 106 — Financeiro rico: UI do portal

## O que entrega

Nova página **Financeiro** no portal (rota `/financial`) com a descrição de
produto do usuário (2026-08-15). Puramente apresentação sobre os endpoints da
Spec 105 (e reuso das rotas existentes Carteira/Vendas). Padrão UX da Spec 73.

1. **Filtros temporais**: alternância rápida Hoje/Ontem/Semana/Mês/Todo
   Período (segments).
2. **Resumo de Pix**: contadores Pix Gerados / Pix Pagos / Pendentes.
3. **Gráfico de métodos de pagamento**: barra visual da participação do Pix no
   faturamento (do `methodsPixPercent`).
4. **Tabela de transações recentes**: filtros por Gateway, Status e Fluxo;
   colunas Cliente (nome do lead ou `buyerIdHash`), Produto/Plano, Adquirente,
   Data/hora, Bruto, Líquido.
5. **Resumo do período (lateral)**: Ticket Médio, Expirados, Reembolsos, Leads
   Únicos.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — módulo Financeiro. Hoje o portal
tem Carteira (Spec 22) e Vendas (Spec 25) separados; a página Financeiro
consolida com os novos dados (Spec 105).

## Critérios de aceite

1. **Filtros temporais**: segments (Hoje/Ontem/Semana/Mês/Tudo) → recarregam
   `summary` + `transactions` com o `period`.
2. **Resumo de Pix**: 3 cards (Gerados/Pagos/Pendentes) + `grossVolume`.
3. **Gráfico de métodos**: barra única (Pix 100%) ou divisão Pix/estornos —
   decisão na UI (só Pix existe; mostrar "Pix 100%" quando houver volume).
4. **Tabela**: colunas Cliente (nome do lead ou `buyerIdHash` truncado com
   tooltip), Produto (`productName` ou "—"), Gateway (PADRAO ou provider),
   Status (badge), Fluxo, Data, Bruto, Líquido. Filtros: Gateway (select),
   Status (select), Fluxo (select). Paginação.
5. **Resumo do período lateral**: Ticket Médio, Expirados, Reembolsos, Leads
   Únicos (cards/list).
6. **UX**: Spinner, empty states, `ErrorBanner`, `formatMoney` (Spec 73).
7. **Rotas/SPA**: `/financial` no `PortalSpaController` + `spaPublicPaths()`
   (lição Specs 80/84).
8. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-financial-ui.spec.js`) — filtro hoje/ontem, resumo, tabela com
   gateway, resumo lateral; suíte completa sem regressão.

## Dependências

- **Spec 105** (backend) — endpoints financeiros.
- **Spec 73** — UX.
- **Spec 22/25** — rotas existentes (Carteira/Vendas) preservadas no menu.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — tabela/resumo
  escopados pelo perfil ativo (Spec 124); respeitar a lente Bot/Gateway do
  shell ao exibir.

## Fora de escopo

- **Backend** — Spec 105.
- **Exportação/PDF** — evolução.
- **Multi-método** — só Pix hoje (o gráfico reflete).
