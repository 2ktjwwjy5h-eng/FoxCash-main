---
name: Shell do portal — header global + menu lateral expandido (UI)
status: audited
---

# Spec 102 — Task: Shell do portal (UI)

## F0 — Header global

- [x] **T0.1** — `HeaderGlobal.tsx`: saudação/data, billing com barra de meta,
      carrossel de avatares (Spec 101), atalhos (ajuda/notificações/perfil/
      config). `useAsync` + refresh 60s.
- [x] **T0.2** — `LeadAvatar.tsx` (iniciais; clique → `openChat`).
- [x] **T0.3** — `NotificationsMenu.tsx` (dropdown `role=menu`, badge).
- [x] **T0.4** — `ChatContext` (abre o Live Chat do CRM a partir do carrossel).
- [x] **T0.5** — Não quebrar o `ProfileSelector` da Spec 139 (lente Bot/Gateway
      + seletor de perfil 2 níveis) no HeaderGlobal; o menu acomoda o painel do
      contexto; dados do header escopados pelo perfil ativo (Spec 124).

## F1 — Menu em seções

- [x] **T1.1** — `Sidebar.tsx` com `NAV_GROUPS` (Visão Geral/Financeiro/Clientes/
      Automações/Ferramentas/Comunidade/Afiliado); itens reais + `soon`.
- [x] **T1.2** — `PlaceholderPage` ("Em breve") para rotas futuras.
- [x] **T1.3** — `Layout.tsx` integra header + sidebar (skip-link preservado).

## F2 — Rotas/SPA/CSP + build

- [x] **T2.1** — `App.tsx`: rota `/help` (FAQ modal); rotas `soon` via
      `PlaceholderPage`. Nenhuma rota nova real além das existentes nesta spec.
- [x] **T2.2** — `PortalSpaController`/`spaPublicPaths()` verificados (nenhuma
      rota nova real necessária aqui).
- [x] **T2.3** — `npm run build` (tsc) limpo no portal.

## F3 — E2E + fechamento

- [x] **T3.1** — E2E `portal-shell-ui.spec.js` (D6).
- [x] **T3.2** — Suíte E2E completa sem regressão (ordem portal → console).
- [x] **T3.3** — Atualizar roadmap/status.

## Verificação (`/spec-verify 102`)

- [x] Checar critérios ↔ design ↔ task contra o código real (`Layout.tsx`
      atual, `NavLink`, `useAsync`, `PortalSpaController`, `spaPublicPaths`,
      Live Chat da Spec 84, E2E existentes) — sem achados de escopo.

## Implementação (2026-08-17)

- F0–F3 concluídas: `HeaderGlobal` (billing + barra, saudação/data, carrossel de
  avatares, atalhos com badge), `LeadAvatar` (iniciais; clique → `/crm?lead={id}`),
  `NotificationsMenu` (dropdown `role=menu` + badge), `Sidebar` (`NAV_GROUPS` em
  6 seções), `Layout` reescrito (header + sidebar + ProfileSelector da Spec 139
  preservado — D6.1), `HelpModal` (FAQ — modal, sem rota nova), `CrmPage` lê
  `?lead=` e abre o Live Chat (Spec 84).
- **Desvios registrados**: (1) ajuda = **modal FAQ** (não rota `/help` — o
  requirements permite "ou modal"; menos superfície, sem SPA path novo);
  (2) itens futuros = **links desabilitados** "em breve" (não navegam — sem
  rota/PlaceholderPage nesta spec; a página real vem com a spec própria);
  (3) labels do menu seguem o requirements (Meus Robôs/Postagens/Trackeamento) —
  4 E2E existentes atualizados (bots-creator ×2, bots-panel, broadcast-editor ×3,
  tracking).
- Builds limpos: portal (81 módulos) + console (regra do README). E2E
  `portal-shell-ui.spec.js` **2/2** + specs afetados 21/21 + suíte completa verde.

---

## Audit (Spec 102) — 2026-08-17

Implementação auditada com sucesso. Código revisado, testes validados.

**Frontend (Spec 102) — entregue e auditado:**
- `Layout.tsx` com header global: saudação com hora do dia + nome do lojista + data.
- Faturamento do mês (R$ 0,00 sem vendas) + barra de meta do mês.
- Carrossel de avatares de leads recentes (iniciais JS/MO).
- Clique no avatar → Live Chat do CRM abre (`/crm?lead={id}`, Spec 84).
- Menu lateral em seções (NAV_GROUPS: Visão Geral, Financeiro, Clientes, Automações, Ferramentas, Mais) com itens futuros desabilitados "em breve".
- Atalho de notificações com badge (contador do shell) + dropdown com mensagens (Venda aprovada, Saque concluído).
- API sem token → 401.

**Verificações executadas:**
- `npm run build` (portal): limpo, sem erros.
- E2E `portal-shell-ui.spec.js` (Specs 101/102): 2/2 passed.
- Cobertura: C1 (header), C2 (chat via avatar), C3 (menu em seções), C4 (badge) + 401.
