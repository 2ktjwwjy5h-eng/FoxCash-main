# Spec 129 — Design: Landing page pública + cadastro público

## D1. Objetivo

Landing page pública de marketing na raiz do portal (`/`) com identidade visual
do **template aprovado** (protótipo "aaaaa" — tema escuro + laranja-fox),
**frontend-only**, sem dependência nova e sem mudança de contrato HTTP. O
cadastro (Spec 142) já existe e é o destino dos CTAs.

## D2. Arquivos

| Arquivo | Ação | Papel |
|---|---|---|
| `portal/src/pages/LandingPage.tsx` | reescrito | Página da landing (8 blocos: header/hero/métricas/recursos/como funciona/integrações/preços/rodapé) + ícones SVG inline |
| `portal/src/styles.css` | bloco `.landing-*` substituído | Tema escuro + laranja-fox escopado em `.landing` (vars `--fox-*`) |
| `portal/src/App.tsx` | rota `/` | `LandingPage` como rota pública; redirect antigo `/` → `/dashboard` removido |
| `portal/src/main.tsx` | inalterado | `AuthProvider` já envolve `App` — o `useAuth` do header da landing funciona |
| `src/main/java/.../SecurityConfig.java` | inalterado | `/` já está em `spaPublicPaths()` (Spec 46) |
| `src/main/java/.../PortalSpaController.java` | inalterado | `/` já cai no fallback → `index.html` |
| `e2e-tests/tests/landing.spec.js` | novo | E2E da landing (3 cenários) |

## D3. Estrutura do componente

`LandingPage` (default export) compõe sub-componentes no mesmo arquivo (KISS —
um único arquivo para a página, sem abstração):

- `LandingHeader` — `position: fixed` + blur; `useAuth()` para o estado logado
  (`token`, `logout`); nav de âncoras; menu mobile com `useState` (hambúrguer);
  `aria-label`/`aria-expanded`.
- `Hero` — badge, título (com `<span class="landing-hero-accent">`), subtítulo,
  CTA `Link to="/register"`, caption; glow radial via `.landing-glow::before`.
- `Metrics` — 4 cards (dados em `const metrics`).
- `Features` — `section#recursos`, 4 cards com pill.
- `HowItWorks` — `section` (sem id — fora do menu), 4 cards numerados
  (`01`..`04` absoluto no canto).
- `Integrations` — `section#integracoes`, 4 grupos com pills.
- `Pricing` — `section#precos`, card "Modelo transparente".
- `LandingFooter` — logo + nav + copyright.

Ícones: componentes `Icon` + constantes SVG inline (paths estilo lucide,
`stroke="currentColor"`) — **sem lib de ícones**.

## D4. Tema escuro + laranja-fox (escopado)

O bloco `.landing` define as CSS variables locais (não tocam `:root`):

```css
.landing {
  --fox-bg: #211d19;
  --fox-card: #2a251f;
  --fox-card-soft: rgba(255, 255, 255, 0.03);
  --fox-border-soft: rgba(255, 255, 255, 0.08);
  --fox-text: #f4f0ea;
  --fox-muted: #a79c90;
  --fox-primary: #ef7c24;
  --fox-grad: linear-gradient(135deg, #f8933a, #e05a16);
  --fox-shadow: 0 18px 40px -18px rgba(239, 124, 36, 0.55);
  min-height: 100vh;
  background: var(--fox-bg);
  color: var(--fox-text);
}
```

Aproximação hex dos `oklch` do template (`oklch(0.16 0.006 60)` ≈ `#211d19`,
`oklch(0.68 0.2 42)` ≈ `#ef7c24`, gradiente `--gradient-fox` ≈ `#f8933a →
#e05a16`). Todos os seletores da landing usam `--fox-*` — o restante do portal
(tema claro, `--primary: #2b6cb0`) não é afetado.

CTAs: `.landing-cta` = pill com `background-image: var(--fox-grad)` + sombra
fox; hover translateY(-1px). Botão "Sair": `.landing-ghost-btn` (borda + hover
`--fox-card-soft`).

## D5. Rotas

`App.tsx`: `path="/"` → `LandingPage` (pública, fora do `RequireAuth`); o bloco
antigo `<Route path="/" element={<Navigate to="/dashboard" replace />}>` dentro
do layout privado é **removido** (a raiz é a landing; `LoginPage` já navega a
`/dashboard` após login). Fallback `*` → `/` permanece (rota desconhecida cai na
landing, padrão SPA).

`/` público no backend já é suportado: `SecurityConfig.spaPublicPaths()` inclui
`"/"` e `PortalSpaController.forwardToIndex` devolve o `index.html` — **nenhuma
mudança de backend** (verificação ao vivo: `GET /` → 200 `text/html`).

## D6. Acessibilidade

- Navegações com `aria-label`; botão do menu com `aria-expanded` e `aria-label`
  alternado (Abrir/Fechar menu).
- Ícones com `aria-hidden="true"`; textos dos cards como conteúdo real
  (`<p>`, `<h2>/<h3>`).
- Títulos hierárquicos: `h1` no hero, `h2` nas seções, `h3` nos cards.
- Cores: contraste do tema escuro mantido pelo padrão do template (texto
  `#f4f0ea` sobre `#211d19`).

## D7. Testes

- Build: `npm run build` (tsc + vite) do portal; **console rebuildado depois**
  (ordem portal → console — o `emptyOutDir` do portal apaga `static/console/`,
  achado Spec 73).
- E2E `e2e-tests/tests/landing.spec.js` (padrão Playwright da suíte, sem auth):
  1. `/` renderiza a landing (título, badge e títulos das seções);
  2. header deslogado (Login + Criar conta) e CTA do hero → `/register`
     (tela de cadastro com `getByLabel('Nome')`);
  3. nav de âncoras + card de Preços ("Modelo transparente" + valores).
