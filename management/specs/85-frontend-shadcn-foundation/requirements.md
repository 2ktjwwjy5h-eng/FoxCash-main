---
name: Frontend — fundação Shadcn UI
status: discarded
references:
  - "management/vision.md#V62"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/71-master-console/requirements.md"
  - "management/specs/46-portal-frontend/requirements.md"
---

# Spec 85 — Frontend: fundação Shadcn UI

## O que entrega

A **fundação do design system Shadcn UI** nos dois frontends (console +
portal), que hoje são CSS puro sem framework. Esta spec **não migra páginas**
(Specs 86/87) — monta a base sobre a qual tudo passa a ser construído.

1. **Tailwind CSS v4** (`@tailwindcss/vite`) nos dois frontends + CSS de
   entrada com `@import "tailwindcss"`.
2. **Componentes shadcn/ui** (Radix por baixo, copy-paste por app):
   `button`, `card`, `dialog`, `alert-dialog`, `input`, `label`, `select`,
   `table`, `badge`, `tabs`, `sheet`, `dropdown-menu`, `sonner`, `skeleton`,
   `separator`, `tooltip`.
3. **Tema claro + escuro** — CSS variables (padrão shadcn), toggle na topbar,
   persistência (localStorage) e preferência do sistema.
4. **Layout responsivo** — sidebar de navegação que vira `Sheet` (drawer) no
   mobile; topbar com theme toggle; skip-link preservado (Spec 73).
5. **Primitivos migrados** no `ui.tsx` dos dois apps: `Card`/`Field` →
   componentes shadcn; banners (`SuccessBanner`/`ErrorBanner`) → **Sonner**
   preservando `role=status`/`role=alert`; `Spinner` → `Loader2`/`Skeleton`;
   `ConfirmDialog` → `AlertDialog`; `StepUpPrompt` → `Dialog`; `Pagination`
   mantém API, reestilizada.
6. **Zero mudança de contrato HTTP** (camada de apresentação — Spec 73);
   CSP validado para estilos inline do Radix (popper).

## Contexto (por que agora)

Decisão do usuário (2026-08-12): **adotar o estilo Shadcn UI em todo o
frontend** e **exigir responsividade**. Estado atual: CSS puro (~1.000 linhas
nos 2 apps), componentes custom pequenos, **sem Tailwind/lib de UI** — terreno
limpo. Ordem estratégica: a fundação **precede** as telas novas das Specs
76/80/82/84 (nascem no padrão shadcn) e as migrações (86/87).

## Critérios de aceite

1. **Tailwind v4 configurado** nos dois frontends (`@tailwindcss/vite` +
   `@import "tailwindcss"` no CSS de entrada); build Vite verde.
2. **Componentes shadcn/ui adicionados** por app (`src/components/ui/`):
   lista do item 2 do "O que entrega"; sem lib adicional além de Radix/
   Tailwind/shadcn (padrão copy-paste).
3. **Tema claro + escuro**: CSS variables (shadcn) em `globals.css`; toggle
   na topbar; persiste em `localStorage`; respeita preferência do sistema no
   primeiro acesso; sem FOUC (script inline no HTML antes do app).
4. **Layout responsivo**: sidebar vira `Sheet` em < `lg`; conteúdo com
   `max-width` + padding responsivo; skip-link preservado; navegação ativa
   destacada; topbar com theme toggle + breadcrumb/título da página.
5. **Primitivos migrados** (dois apps): `Card` (shadcn `Card`),
   `Field` (shadcn `Label`+`Input`), banners → `Sonner` (`role=status`/
   `role=alert` preservados via `toast` acessível), `Spinner` →
   `Loader2`/`Skeleton`, `ConfirmDialog` → `AlertDialog` (API compatível:
   `title`/`description`/`confirmLabel`/`tone`/`onConfirm`), `StepUpPrompt` →
   `Dialog` (mesma API), `Pagination` reestilizada.
6. **CSP**: `SecurityConfig` (console/portal) validado contra estilos inline
   do Radix (popper usa `style` inline); se necessário, ajuste mínimo
   documentado (não afeta outras rotas).
7. **Zero mudança de contrato HTTP**: nenhuma rota/payload alterada; o build
   servido pelo Spring (`ConsoleSpaController`/portal) continua funcionando.
8. **Ambos os frontends**: a fundação existe nos 2 (componentes e tema
   iguais); `npm run build` (tsc) limpo nos 2; ordem de build preservada
   (portal antes do console — achado da Spec 73).
9. **Sem regressão visual funcional**: telas que ainda não migraram (antes
   das Specs 86/87) continuam funcionando — a fundação é aditiva (não quebra
   o CSS antigo até a migração).
10. **Testes**: smoke E2E (tema alterna e persiste; nav vira drawer no
    viewport mobile; AlertDialog/Dialog abrem/fecham com foco correto);
    `npm run build` limpo nos 2; suíte E2E existente sem regressão.

## Dependências

- **Specs 46/71/73** — padrão de tela/UX que a fundação padroniza.
- **Nenhuma de backend** — camada de apresentação.
- **Pré-requisito** para as Specs 86/87 (migrações) e 76/80/82/84 (páginas
  novas).

## Fora de escopo

- **Migração das páginas** → Specs 86 (console) e 87 (portal).
- **Mudança de contrato HTTP** — proibida nesta fase.
- **Novas funcionalidades** — só apresentação.
