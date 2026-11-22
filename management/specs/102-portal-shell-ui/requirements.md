---
name: Shell do portal — header global + menu lateral expandido (UI)
status: audited
references:
  - "management/vision.md#V77"
  - "management/specs/101-portal-shell-backend/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/83-crm-chat/requirements.md"
  - "management/specs/74-registro-bots/requirements.md"
  - "management/specs/79-broadcast-motor/requirements.md"
---

# Spec 102 — Shell do portal: header global + menu lateral expandido (UI)

## O que entrega

O **shell visual** do portal do lojista (Spec 46/73) com o **header global** da
descrição de produto do usuário (2026-08-15) e o **menu lateral expandido**.
Puramente apresentação sobre o backend da Spec 101 (e as rotas existentes).

1. **Header global** (`Layout.tsx`):
   - **Indicador de faturamento em tempo real**: `R$ 293,58` do período com
     **barra de progresso de meta** (do `shell.billing`).
   - **Saudação e data**: "Boa madrugada, Gansa" + data corrente (do
     `shell.greeting`).
   - **Carrossel de leads recentes**: avatares circulares com iniciais (do
     `shell.recentLeads`) → clique abre o **Live Chat do CRM** (Spec 84).
   - **Atalhos de ação rápida**: ícones para ajuda, notificações (com badge do
     `shell.notifications.unreadCount`), perfil e configurações (navegam para
     rotas existentes/placeholders).
2. **Menu lateral expandido em seções** (agrupamento visual):
   - **Visão Geral**: Dashboard, Ranking (→ `/ranking`, Spec 114), Premiações
     (→ `/rewards`, Spec 114), Análises (placeholder → redireciona ao Dashboard).
   - **Financeiro**: Carteira, Vendas, Financeiro (→ `/financial`, Spec 106).
   - **Clientes**: CRM (→ `/crm`).
   - **Automações**: Meus Robôs (→ `/bots`), Meus Fluxos (→ `/flows`, Spec 118),
     Redirecionadores (→ `/redirects`, Spec 118), Remarketing (→ `/remarketing`,
     Spec 118), Postagens (→ `/broadcasts`, já `audited`).
   - **Ferramentas**: Gateways (→ `/gateways`, Spec 108), Trackeamento (→
     `/tracking`), Checkout (→ `/checkouts`, Spec 116), Bio Link (→ `/biolink`,
     Spec 116), Webhooks (→ `/webhooks`), Cupons & Sorteios (→ `/sorteio`, Spec 116).
   - **Comunidade** (→ `/community`, Spec 119) e **Afiliado** (→ `/affiliate`,
     Spec 112).
   - Itens de rotas **não implementadas ainda** aparecem como placeholders
     desabilitados ("em breve") ou navegam (decisão: navegar com página
     placeholder genérica "Em breve" — KISS).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — barra de topo geral e menu
lateral do portal de referência. Lacuna: o `Layout.tsx` atual tem só o menu
plano (13 itens) e o nome no rodapé; sem header. Backend: Spec 101 (criada
junto) fornece os dados.

## Critérios de aceite

1. **Header global**: renderiza `shell` (Spec 101) — saudação/data, faturamento
   com barra de progresso, carrossel de avatares (iniciais; sem foto → inicial),
   atalhos com badges. **Loading** com placeholder (Spec 73) e erro com
   `ErrorBanner` (não derruba o layout).
2. **Carrossel de leads**: clicar num avatar abre o **Live Chat do CRM** do
   lead (Spec 84 — navega `/crm` e abre o modal com o lead). Sem lead → o
   carrossel não renderiza (empty).
3. **Menu lateral expandido**: seções com cabeçalho, itens com ícone (emoji/
   SVG simples) e badge de status (ex.: "novo" em Clientes com não lidas);
   navegação para as rotas existentes; itens futuros desabilitados ou com
   placeholder "Em breve".
4. **Atalhos**: ajuda (nova rota `/help` com FAQ simples ou modal), notificações
   (dropdown com as `items` do shell), perfil (→ `/security` ou modal),
   configurações (→ `/credentials`).
5. **Responsividade**: sidebar colapsável em telas estreitas (CSS puro — sem
   lib; o padrão do console já tem; evolução responsiva continua fora de
   escopo — decisão descarte Shadcn 2026-08-14).
6. **Acessibilidade**: skip-link preservado, links com `aria-label`, dropdown de
   notificações com `role="menu"` (padrão Spec 73).
7. **SPA paths/CSP**: novas rotas (`/help`, `/financial`, `/ranking`, `/rewards`,
   `/gateways`, `/links`, `/flows`, `/redirects`, `/remarketing`, `/checkouts`,
   `/biolink`, `/sorteio`, `/affiliate`, `/community`) adicionadas ao
   `PortalSpaController` + `spaPublicPaths()` do `SecurityConfig` (lição Specs
   80/84) — **quando as páginas forem implementadas**; nesta spec, só as que já
   existem são links reais (as futuras são placeholders).
8. **Testes**: `npm run build` (tsc) limpo no portal + E2E Playwright
   (`portal-shell-ui.spec.js`): login → header (saudação/faturamento/barra/
   carrossel) → abrir chat de um lead → menu expandido com seções; suíte
   completa sem regressão.

## Dependências

- **Spec 101** (backend shell) — dados do header.
- **Spec 46/73** (portal/UX) — Layout atual, `ui.tsx`, banners, modais.
- **Spec 84** (CRM UI) — Live Chat para o carrossel.
- **Specs 74/79** — rotas existentes (Bots/Postagens) no menu.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — o **HeaderGlobal**
  já contém o `ProfileSelector` da Spec 139: **seletor de lente (Bot |
  Gateway) antes do seletor de perfil** (2 níveis), painel do contexto e
  tooltip. O shell novo (header global + menu em seções) **não pode quebrar
  nem duplicar** esse seletor — a lente `foxcash.portal.lens` persiste no
  localStorage e filtra os perfis por `scope` (`GENERAL`/`BOT_ONLY`/
  `GATEWAY_ONLY`, V76); o menu deve acomodar o painel do contexto sem escondê-lo.

## Fora de escopo

- **Implementação das páginas futuras** (Financeiro, Ranking, Gateways, Links,
  Fluxos, Checkout, Bio Link, Sorteio, Afiliado, Comunidade) — specs próprias
  (105–119).
- **Feed completo de notificações** — só dropdown leve do shell.
- **Responsividade mobile completa** — evolução futura.
