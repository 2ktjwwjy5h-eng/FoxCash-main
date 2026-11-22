---
name: Gestão de Links e Campanhas — UI do portal
status: verified
references:
  - "management/vision.md#V81"
  - "management/specs/109-links-filtro de tráfego-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
---

# Spec 110 — Gestão de Links e Campanhas: UI do portal

## O que entrega

Página **Links** no portal (rota `/links`) com a descrição de produto do
usuário (2026-08-15) — a tela de **Gestão de Links e Campanhas (Distribuição)**.
Puramente apresentação sobre os endpoints da Spec 109. Padrão UX da Spec 73.

1. **KPIs globais**: links ativos, total de cliques, links com filtro de tráfego.
2. **Busca e filtros**: por nome/URL; filtros por status (Todos/Ativos/
   Inativos/Cloaker) e ordenação por data.
3. **Tabela de links**: Link (nome, status, domínio/URL camuflada, código
   `shk=`/slug, tag CLOAKER), Entrega (fluxo de venda + taxa de aprovação —
   quando houver), **Desempenho** (cliques, vendas, receita, conversão) e
   **Ações** (Copiar, Preparar campanha, Menu — editar regras do filtro de tráfego,
   pausar, deletar).
4. **Modal Preparar Campanha**: estrutura links com UTMs (origem/mídia/
   campanha) e filtro de tráfego (página intermediária).
5. **Gerenciador de domínios** e **códigos de venda** (seções/tabs).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Gestão de Links e Campanhas do
portal de referência (meus links, códigos de venda, preparar campanha,
domínios, página intermediária, filtro de tráfego). Backend: Spec 109 (criada junto).

## Critérios de aceite

1. **KPIs**: 3 cards (links ativos, cliques totais, com filtro de tráfego) — dados da
   listagem (Spec 109) agregados na UI (ou endpoint — decisão: somar na UI a
   partir da listagem, sem endpoint extra).
2. **Tabela**: colunas conforme descrição; tag **CLOAKER** quando
   `filtro de tráfegoEnabled`; badge de status; código `shk={slug}`; **Copiar** com
   feedback (Spec 73 — "Copiado!"); **Menu** com editar filtro de tráfego (modal),
   pausar/reativar, deletar (ConfirmDialog danger).
3. **Modal Preparar Campanha**: campos `utm_source/medium/campaign/term/
   content`, destino, domínio, filtro de tráfego + página intermediária; validação;
   sucesso → URL pronta para copiar.
4. **Domínios e sale codes**: seções com CRUD (domínios máx 5; sale codes
   únicos) — modais simples.
5. **UX**: Spinner, empty states, `ErrorBanner`/`SuccessBanner` (Spec 73),
   `ConfirmDialog`.
6. **Rotas/SPA**: `/links` no `PortalSpaController` + `spaPublicPaths()`
   (lição Specs 80/84).
7. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-links-ui.spec.js`) — criar link → copiar → preparar campanha →
   toggle filtro de tráfego → stats; suíte completa sem regressão.

## Dependências

- **Spec 109** (backend) — endpoints.
- **Spec 73** — UX.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — KPIs/tabela
  escopados pelo perfil ativo (Spec 124); respeitar a lente Bot/Gateway.

## Fora de escopo

- **Backend** — Spec 109.
- **Remarketing/redirecionadores** — Spec 117/118.
