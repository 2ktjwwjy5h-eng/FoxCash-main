# Spec 129 — Task: Landing page pública + cadastro público

> Status: `audited` (2026-08-19). Ciclo SDD completo nesta sessão:
> `/spec-new` + `/spec-verify` + `/spec-implement` + `/spec-audit`.

## F0 — LandingPage.tsx (código)

- [x] F0.1 Componentes SVG inline (ícones estilo lucide — sem dependência).
- [x] F0.2 `LandingHeader` (fixo + blur + estado logado via `useAuth` + menu
      mobile) e `LandingFooter`.
- [x] F0.3 Seções: `Hero`, `Metrics`, `Features` (#recursos), `HowItWorks`,
      `Integrations` (#integracoes), `Pricing` (#precos) — copy fiel ao template
      "aaaaa".
- [x] F0.4 CTAs → `/register` (hero/preços "Criar conta gratuita"; header/rodapé
      "Criar conta") e "Login" → `/login`.

## F1 — CSS (styles.css)

- [x] F1.1 Bloco `.landing-*` substituído: vars `--fox-*` escopadas, tema escuro
      + laranja-fox, cards, pills, pricing card, glow, header fixo, menu mobile.
- [x] F1.2 Responsivo `@media (max-width: 768px)`: nav/actions escondem, menu
      hambúrguer aparece, hero/typo ajustados.

## F2 — Rota (App.tsx)

- [x] F2.1 `/` → `LandingPage` (pública); redirect antigo `/` → `/dashboard`
      removido (o `LoginPage` segue navegando a `/dashboard`).
- [x] F2.2 Verificação backend: `/` já em `spaPublicPaths()` + fallback do
      `PortalSpaController` — **sem mudança** (200 `text/html`).

## F3 — Build

- [x] F3.1 `npm run build` do portal (tsc + vite) limpo — 83 módulos (após a
      remoção do WIP de LeadsPage do `src/`).
- [x] F3.2 Console rebuildado na sequência (ordem portal → console — `emptyOutDir`
      do portal apaga `static/console/`).

## F4 — E2E

- [x] F4.1 `e2e-tests/tests/landing.spec.js` (3 cenários: render das seções,
      header deslogado + CTA → `/register`, nav de âncoras + Preços).
- [x] F4.2 Execução da suíte E2E da landing — **3/3 verde** (app dev no ar;
      strict-mode resolvido com `.first()` — textos repetem em Métricas/Preços e
      nav header/rodapé).

## F5 — Docs

- [x] F5.1 requirements.md/design.md/task.md criados (`129-landing-cadastro/`).
- [x] F5.2 Visão V105 (vision-value-block).
- [x] F5.3 Roadmap: Spec 129 `implemented` + nota de implementação.
- [x] F5.4 `/spec-audit 129` — **8/8 critérios OK, 0 achados** (detalhe no
      §Auditoria abaixo); status `audited`.
- [x] F5.5 Commit (código + docs) e `docs-consistency` OK.

## Verificação (gates)

- [x] `npm run build` portal limpo (tsc + vite — 83 módulos).
- [x] `npm run build` console limpo (ordem portal → console).
- [x] E2E `landing.spec.js` **3/3 verde** + regressão `portal-shell-ui.spec.js`
      **2/2 verde** (fluxo avatar → `/crm?lead=` intacto — o WIP de divisão do
      CRM foi preservado em `portal/.tmp-template/wip-crm-split/` e NÃO faz
      parte deste commit).
- [x] `scripts/check-docs-consistency.sh` OK.

## Auditoria (2026-08-19)

Critérios verificados contra o código real/ao vivo:

- **C1 (rota `/` pública)** — `GET /` → **200 `text/html`** (SPA via
  `PortalSpaController`), sem auth; `/` presente em
  `SecurityConfig.spaPublicPaths()` (Spec 46 — verificado no código, L279).
- **C2 (seções do template)** — hero (badge/título/subtítulo/CTA), métricas (4),
  recursos (4), como funciona (4 numerados), integrações (4 grupos), preços
  (card "Modelo transparente") e rodapé — conferidos no código + E2E 1/1.
- **C3 (header com estado)** — `useAuth()`: deslogado → Login + "Criar conta";
  logado → "Painel" (`/dashboard`) + "Sair" (logout → `/login`); menu mobile
  hambúrguer com `aria-label`/`aria-expanded`.
- **C4 (CTAs)** — "Criar conta gratuita" (hero/preços) e "Criar conta"
  (header/rodapé) → `/register`; "Login" → `/login` (E2E 2/2 prova a navegação
  ao cadastro).
- **C5 (identidade escopada)** — vars `--fox-*` e tema escuro/laranja-fox só em
  `.landing`; tema claro do portal intacto (regressão `portal-shell-ui` 2/2).
- **C6 (sem dependência)** — ícones SVG inline; nenhuma lib nova; zero mudança
  de contrato HTTP.
- **C7 (build)** — portal 83 módulos + console 67 (ordem portal → console)
  limpos.
- **C8 (E2E)** — `landing.spec.js` **3/3** verde.

**0 achados abertos.** Fim do ciclo SDD (roadmap/requirements/design/task
`audited`).
