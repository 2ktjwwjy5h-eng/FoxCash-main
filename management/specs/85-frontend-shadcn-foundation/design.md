---
name: Frontend — fundação Shadcn UI
status: discarded
---

# Spec 85 — Design: Fundação Shadcn UI

## D1 — Conceito

A fundação é **aditiva**: instala Tailwind v4 + shadcn/ui e cria os
componentes/tema/layout, **sem quebrar** o CSS e as páginas atuais (que são
migradas nas Specs 86/87). Os dois frontends são apps React/Vite separados —
cada um recebe a mesma fundação (shadcn é copy-paste por app).

### D1.1 Decisões do usuário (2026-08-12) → regras

| Decisão | Regra |
|---|---|
| Escopo: os dois frontends | fundação nos 2; migrações 86 (console) / 87 (portal) |
| Dark mode sim | claro + escuro; toggle + localStorage + preferência do sistema |
| 3 specs | 85 fundação → 87 portal (antes da 86) → 86 console |
| Sem mudança de contrato | camada de apresentação apenas (Spec 73) |

## D2 — Setup por app (console/ e portal/)

1. `npm i tailwindcss @tailwindcss/vite` + plugin no `vite.config.ts` +
   `@import "tailwindcss";` no `src/index.css` (substitui/coexiste com
   `styles.css` — **aditivo**: `styles.css` antigo permanece até a migração).
2. `src/lib/utils.ts` (`cn` com `clsx` + `tailwind-merge`).
3. `src/components/ui/*` via shadcn CLI (ou manual): button, card, dialog,
   alert-dialog, input, label, select, table, badge, tabs, sheet,
   dropdown-menu, sonner, skeleton, separator, tooltip.
4. `globals.css` — CSS variables shadcn (light + dark), paleta
   `neutral`/`zinc` base (primary customizável depois).
5. `components.json` (config do shadcn).

## D3 — Tema (claro + escuro)

- `ThemeProvider` (contexto) — classe `.dark` no `<html>`; estado
  `light | dark | system`; primeiro acesso = preferência do sistema; troca
  persistida em `localStorage` (`foxcash.theme`).
- **Anti-FOUC**: `<script>` inline no `index.html` que lê o localStorage e
  aplica `.dark` antes do bundle.
- Toggle na topbar (`DropdownMenu` ou botão com `Sun`/`Moon` ícones).

## D4 — Layout responsivo

- `AppLayout` (por app): sidebar fixa em `lg+` com links de navegação (RBAC-
  aware no console — Spec 69) + botão "abrir menu" em < `lg` → **`Sheet`**
  (drawer) com a mesma navegação; topbar com título da página + theme toggle;
  conteúdo `<main>` com `max-w` + `px-4 md:px-6 lg:px-8`.
- Skip-link preservado (Spec 73): primeiro foco navegável.
- Breakpoints: `sm (640) / md (768) / lg (1024) / xl (1280)`.

## D5 — Primitivos migrados (API compatível)

| Atual (`ui.tsx`/components) | Novo | API preservada |
|---|---|---|
| `Card` | shadcn `Card` | `title` + children |
| `Field` | shadcn `Label`+`Input` | `label` + props input |
| `SuccessBanner`/`ErrorBanner` | **Sonner** `toast.success/error` | wrappers `showSuccess`/`showError` com `role` (Sonner expõe) |
| `Spinner` | `Loader2` (lucide) + `animate-spin` / `Skeleton` | `<Spinner/>` |
| `ConfirmDialog` | shadcn `AlertDialog` | `title/description/confirmLabel/tone/onConfirm` (tone → variante) |
| `StepUpPrompt` | shadcn `Dialog` | API atual (403 → prompt → repete) |
| `Pagination` | reestilizada com shadcn `Button` | API atual |

- Ícones: **lucide-react** (padrão shadcn).
- Toasts: `Toaster` (Sonner) montado no layout dos 2 apps.

## D6 — CSP (risco verificado)

- Radix popper usa `style` inline (posicionamento). **Verificado no
  `/spec-verify 85` (2026-08-12): o CSP atual já inclui
  `style-src 'self' 'unsafe-inline'`** (`SecurityConfig` linha ~211, tanto
  console quanto portal) — os estilos inline do Radix **já são permitidos**;
  **nenhum ajuste de CSP é necessário** nesta spec (apenas validar em E2E que
  o popper renderiza).

## D7 — Configuração/testes

- `npm run build` (tsc) limpo nos 2; Vite build OK.
- Smoke E2E: toggle de tema persiste após reload; nav vira drawer no
  viewport mobile (Playwright `setViewportSize`); `AlertDialog`/`Dialog`
  abrem com foco correto e fecham no Escape.
- Suíte E2E existente sem regressão (a fundação é aditiva; páginas não
  migradas continuam no CSS antigo).

## D8 — Fora de escopo (desta spec)

- Migração das páginas (86/87), dark mode por página (nasce no tema),
  customização de marca (paleta própria — evolução).
