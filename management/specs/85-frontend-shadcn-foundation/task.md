---
name: Frontend — fundação Shadcn UI
status: discarded
---

# Spec 85 — Task: Fundação Shadcn UI

## F0 — Setup por app (critérios 1–2)

- [ ] T0.1: `console/` — `npm i tailwindcss @tailwindcss/vite clsx tailwind-merge
      lucide-react` + plugin no `vite.config.ts` + `@import "tailwindcss";` no
      CSS de entrada; `components.json`.
- [ ] T0.2: `portal/` — idem (T0.1).
- [ ] T0.3: `src/lib/utils.ts` (`cn`) nos 2 apps.
- [ ] T0.4: shadcn components adicionados nos 2 apps: button, card, dialog,
      alert-dialog, input, label, select, table, badge, tabs, sheet,
      dropdown-menu, sonner, skeleton, separator, tooltip (D2.3).
- [ ] T0.5: `globals.css` (CSS variables light + dark, paleta base) nos 2
      apps (D2.4).
- [ ] T0.6: `npm run build` (tsc) limpo nos 2 apps com a fundação instalada.

## F1 — Tema (critério 3)

- [ ] T1.1: `ThemeProvider` + hook `useTheme` (light/dark/system;
      localStorage `foxcash.theme`; aplica `.dark` no `<html>`).
- [ ] T1.2: Script anti-FOUC no `index.html` dos 2 apps (lê localStorage
      antes do bundle).
- [ ] T1.3: Toggle de tema na topbar (ícones Sun/Moon).
- [ ] T1.4: Teste — tema alterna, persiste após reload, respeita preferência
      do sistema no 1º acesso.

## F2 — Layout responsivo (critério 4)

- [ ] T2.1: `AppLayout` novo (por app): sidebar `lg+` + `Sheet` (drawer) em
      < `lg` com a mesma navegação (RBAC-aware no console — Spec 69);
      topbar com título + theme toggle; `<main>` com `max-w` + padding
      responsivo.
- [ ] T2.2: Skip-link preservado (Spec 73) no novo layout.
- [ ] T2.3: Teste — nav vira drawer no viewport mobile (Playwright
      `setViewportSize`), links funcionam, navegação ativa destacada.

## F3 — Primitivos migrados (critério 5)

- [ ] T3.1: `ui.tsx` dos 2 apps — `Card` (shadcn), `Field` (Label+Input),
      `Spinner` (Loader2/Skeleton), `Pagination` reestilizada (API atual).
- [ ] T3.2: Banners → Sonner: wrappers `showSuccess`/`showError` com
      `role=status`/`role=alert`; `Toaster` no layout dos 2 apps.
- [ ] T3.3: `ConfirmDialog` → `AlertDialog` (API: title/description/
      confirmLabel/tone/onConfirm; tone danger/primary → variante) nos 2
      apps; comportamento de foco nativo do Radix validado.
- [ ] T3.4: `StepUpPrompt` (console) → shadcn `Dialog` (mesma API; fluxo
      403 → prompt → repete ação intacto).
- [ ] T3.5: Testes unit/component — APIs compatíveis (ex.: `ConfirmDialog`
      com `tone` mapeia para a variante correta).

## F4 — CSP + fechamento (critérios 6–10)

- [ ] T4.1: Validar CSP do `SecurityConfig` (console/portal) contra estilos
      inline do Radix (popper); ajuste mínimo documentado se necessário
      (D6) + teste de regressão das rotas.
- [ ] T4.2: Smoke E2E — tema persiste; drawer no mobile; AlertDialog/Dialog
      abrem com foco e fecham no Escape.
- [ ] T4.3: Suíte E2E existente sem regressão (fundação aditiva).
- [ ] T4.4: `npm run build` limpo nos 2 (ordem: portal antes do console —
      achado da Spec 73).
- [ ] T4.5: `/spec-verify 85` e depois `/spec-audit 85` (critérios 1–10).
