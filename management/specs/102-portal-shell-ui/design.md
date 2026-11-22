---
name: Shell do portal — header global + menu lateral expandido (UI)
status: audited
---

# Spec 102 — Design: Shell do portal (UI)

## D1 — Conceito

Reescrita do `Layout.tsx` do portal (Spec 46) com **header global** + **menu
em seções**, no padrão UX da Spec 73 (CSS puro + `ui.tsx`). O header consome
`GET /api/v1/portal/shell` (Spec 101) via `useAsync` com refresh periódico
(curto, ex.: 60s) para o "tempo real" do faturamento.

```text
portal/src/
  components/Layout.tsx        → header global + sidebar em seções
  components/HeaderGlobal.tsx  → saudação/billing/carrossel/atalhos
  components/LeadAvatar.tsx    → avatar circular com iniciais
  components/NotificationsMenu.tsx → dropdown (role=menu) do shell.notifications
  components/Sidebar.tsx       → menu em seções (NAV_GROUPS)
  App.tsx                      → rotas novas (placeholder "Em breve")
```

## D2 — Header global (`HeaderGlobal`)

- **Grid CSS**: `[billing] [saudação] [carrossel] [atalhos]` em uma linha
  (wrap em telas estreitas).
- **Billing**: `R$ {billedAmount}` + barra `<progress>`/div com `width:
  progressPercent%` + label `{goalAmount}`. Cor por progresso (verde ≥100,
  amarelo ≥50, vermelho <50).
- **Saudação**: `{salutation}, {name}` + `{date}` (já formatada no backend).
- **Carrossel**: `<LeadAvatar>` por lead; clique → contexto global
  `openChat(leadId)` (Spec 84) — um `ChatContext` (React Context) exposto pelo
  Layout que navega `/crm?lead={id}` e abre o modal.
- **Atalhos**: botões com ícone (SVG inline) — ajuda (modal FAQ simples),
  notificações (`NotificationsMenu` com badge `unreadCount`), perfil (→
  `/security`), config (→ `/credentials`).

## D3 — Menu lateral em seções (`Sidebar`)

- **`NAV_GROUPS`**: array de `{ section, items: [{label, to, icon, badge?, soon?}] }`
  — grupos da descrição (Visão Geral/Financeiro/Clientes/Automações/
  Ferramentas/Comunidade/Afiliado).
- **Itens `soon: true`** (rotas de specs futuras ainda não implementadas):
  renderizar link desabilitado com tooltip "Em breve" (não quebra navegação).
- **Itens reais**: links `<NavLink>` com estado ativo (padrão atual).
- **Badge**: `{label: 'novo', to: '/crm', badge: crmUnreadCount}` (do shell).

## D4 — Placeholder "Em breve"

`PlaceholderPage` (rota `/:feature`) — título da feature + texto "Disponível em
breve" + botão voltar. Cada spec futura substitui a rota.

## D5 — Rotas novas nesta spec

Apenas as que têm página real hoje (`/crm`, `/bots`, `/broadcasts`, `/tracking`,
`/webhooks`, `/credentials`, `/security`, `/wallet`, `/sales`, `/charges`,
`/destinations`, `/withdraw`, `/dashboard`) + `/help` (FAQ modal). As demais
são `soon` (a página real vem com a spec própria).

## D6 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-shell-ui.spec.js`: login → header (saudação "Boa", data,
  faturamento + barra, avatares) → clique num avatar → Live Chat abre →
  menu expandido com seções → atalho notificações mostra badge → 401 redirect.

## D6.1 — Não ignorar a Spec 139 (lente + escopo)

O `HeaderGlobal` **já contém** o `ProfileSelector` da Spec 139 (`in-progress`
2026-08-16): seletor de lente **Bot | Gateway** (segmented) **antes** do seletor
de perfil, painel do contexto (frota de bots / rota da lente Gateway), tooltip e
banner de troca. O shell desta spec **não pode quebrar nem duplicar** esse
seletor — a lente `foxcash.portal.lens` (localStorage) filtra `visibleProfiles`
por `scope` (`GENERAL`/`BOT_ONLY`/`GATEWAY_ONLY`, V76). O menu em seções deve
acomodar o painel do contexto; dados do header (faturamento/leads) são escopados
pelo perfil ativo (Spec 124).

## D7 — Wireframe do shell (registro de design 2026-08-16)

Visual de referência do shell completo (header global + sidebar em seções),
aprovado no detalhamento do Gap 4.

```
┌────────────────────────────────────────────────────────────────────────┐
│  FoxCash │  R$ 293,58  ████████░░ 72%    │ Boa madrugada, Gansa      │
│           │  Meta do mês: R$ 400,00       │ 16 ago 2026 · 02:47       │
│           │  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐    │ [?] (3)             │
│           │  │ML│ │GK│ │RT│ │AS│ │JD│    │  (leads recentes → CRM)   │
├───────────┼────────────────────────────────────────────────────────────┤
│ ▼ VISÃO   │                                                            │
│   Dashboard│                                                            │
│   Ranking │                                                            │
│   Premiac.│                                                            │
│   Análises│                                                            │
│ ▼ FINANC. │                                                            │
│   Carteira│                                                            │
│   Vendas  │                                                            │
│   Finance.│                                                            │
│ ▼ CLIENTES│                                                            │
│   CRM     │                                                            │
│ ▼ AUTOMA. │       CONTEÚDO DA PÁGINA                                   │
│   Meus    │       (rota ativa)                                         │
│   Robôs   │                                                            │
│   Fluxos  │                                                            │
│   Redirec.│                                                            │
│   Remark. │                                                            │
│   Postag. │                                                            │
│ ▼ FERRAM. │                                                            │
│   Gateways│                                                            │
│   Track.  │                                                            │
│   Checkout│                                                            │
│   BioLink │                                                            │
│   Webhooks│                                                            │
│   Sorteio  │                                                            │
│ ▼ MAIS    │                                                            │
│   Comunid.│                                                            │
│   Afiliado│                                                            │
│───────────│                                                            │
│  [perfil] │                                                            │
│  [Sair]   │                                                            │
└───────────┴────────────────────────────────────────────────────────────┘
```

Notas de implementação:
- **Header**: grid CSS `[billing] [saudação] [carrossel] [atalhos]` (D2);
  itens de rotas futuras (Financeiro, Ranking, etc.) aparecem como placeholders
  "Em breve" que navegam para uma página genérica até a spec própria entrar.
- **Sidebar**: `NAV_GROUPS` (D3) — Visão Geral / Financeiro / Clientes /
  Automações / Ferramentas / Mais (Comunidade, Afiliado).
