---
name: Automações avançadas — Meus Fluxos, Redirecionadores e Remarketing (UI)
status: verified
references:
  - "management/vision.md#V85"
  - "management/specs/117-automacoes-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/110-links-filtro de tráfego-ui/requirements.md"
---

# Spec 118 — Automações avançadas: Meus Fluxos, Redirecionadores e Remarketing (UI)

## O que entrega

As páginas do portal para **Meus Fluxos** (rota `/flows`), **Redirecionadores**
(rota `/redirects`) e **Remarketing** (rota `/remarketing`) com a descrição de
produto do usuário (2026-08-15). Puramente apresentação sobre os endpoints da
Spec 117 (redirecionadores reusam a Spec 110). Padrão UX da Spec 73.

1. **Meus Fluxos**: editor de funil de conversação — lista de fluxos + editor
   de nós sequenciais (tipo MESSAGE/SEND_LINK/ADD_TO_GROUP/GRANT_ROLE, conteúdo,
   reorder sobe/desce), ativar/desativar.
2. **Redirecionadores**: lista de `traffic_links` com filtro de tráfego (Spec 110) +
   criar com filtro de tráfego; KPIs (cliques) — visão focada.
3. **Remarketing**: CRUD de campanhas (nome, mensagem, `send_after_minutes`) +
   histórico de disparos (log).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Automações do portal de
referência. Backend: Spec 117 (criada junto).

## Critérios de aceite

1. **FlowsPage**: lista (nome, status, nº nós) + editor com nós sequenciais
   (select tipo + conteúdo; botões ↑/↓ reorder; salvar 200). Empty state.
2. **RedirectsPage**: reuso da tabela da Spec 110 filtrada por filtro de tráfego; botão
   "Novo redirecionador" (modal — cria `traffic_link` com filtro de tráfego).
3. **RemarketingPage**: CRUD de campanhas (validação `send_after_minutes ≥ 5`)
   + tabela de logs (transação, lead, status, data) por campanha.
4. **UX**: Spinner, empty states, banners (Spec 73), `ConfirmDialog`.
5. **Rotas/SPA**: `/flows`, `/redirects`, `/remarketing` no
   `PortalSpaController` + `spaPublicPaths()` (lição Specs 80/84).
6. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-automacoes-ui.spec.js`) — fluxo com nós, redirect, campanha de
   remarketing; suíte completa sem regressão.

## Dependências

- **Spec 117** (backend).
- **Spec 110** (redirecionadores — reuso da UI).
- **Spec 73** (UX).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — listas de fluxos/
  redirects/remarketing escopadas pelo perfil ativo (Spec 124); respeitar a
  lente Bot/Gateway.

## Fora de escopo

- **Backend** — Spec 117.
- **Editor visual drag-and-drop complexo** — nós sequenciais (evolução).
