---
name: Landing page pública + cadastro público (Spec 129)
status: audited
references:
  - "management/vision.md#V105"
  - "management/specs/46-portal-frontend/requirements.md"
  - "management/specs/142-cadastro-ui-portal/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/63-edge-waf-bot-mitigation/requirements.md"
---

# Spec 129 — Landing page pública + cadastro público

## O que entrega

A **landing page pública** do FoxCash (rota `/`, `portal/src/pages/LandingPage.tsx`)
— a vitrine de marketing que converte visitante em lojista — seguindo o
**template visual aprovado pelo usuário** (protótipo "aaaaa": identidade escura +
laranja-fox) dentro da stack atual do portal (**React Router + CSS puro, sem lib
de UI** — Fase 29). O cadastro (`/register`, Specs 142/143) **já existe** e
mostra a API key 1x no sucesso (Spec 12); a landing liga o visitante a ele. A
spec é **frontend-only** — zero mudança de contrato HTTP.

Estrutura entregue (fiel ao template):

1. **Header fixo** com blur — logo `FOX**CASH**`, nav de âncoras
   (`#recursos`/`#integracoes`/`#precos`), ações à direita com **estado logado**
   (`useAuth`): deslogado → "Login" + botão gradiente "Criar conta"; logado →
   "Painel" (`/dashboard`) + "Sair" (logout → `/login`). Menu **mobile**
   (hambúrguer) com os mesmos itens.
2. **Hero** — badge "Gateway + Bot em um só lugar", título "Seu Telegram e
   Discord são suas **máquinas de venda**", subtítulo com `R$ 0,50` por transação
   e `R$ 2,50` por saque, CTA "Criar conta gratuita" → `/register`, caption
   "Cadastro grátis · Sem cartão · Acesso imediato" e glow radial de fundo.
3. **Métricas** — 4 cards com ícone (R$ 0,50 / R$ 2,50 / Gateway nativo / PIX
   instantâneo).
4. **Recursos** (`#recursos`) — "Tudo que você precisa para vender no
   automático": 4 cards (Gateway Nativo FoxCash, Bot para Telegram e Discord,
   Fluxo Básico, Fluxo Avançado) com pill de destaque.
5. **Como funciona** — "Do cadastro à primeira venda em 5 minutos": 4 passos
   numerados (Crie sua conta, Configure seu bot, Ative seu Gateway PIX, Comece a
   vender).
6. **Integrações** (`#integracoes`) — "Conectado ao seu ecossistema de vendas":
   4 grupos (Gateway & Pagamentos, Tráfego e Ads, Tracking Server-Side, Proteção
   e Uptime) com pills.
7. **Preços** (`#precos`) — "Pague apenas pelo que usar": card "Modelo
   transparente" (R$ 0,50 | R$ 2,50), 6 features e CTA.
8. **Rodapé** — logo + nav (Recursos/Integrações/Preços/Criar conta) +
   copyright.

Rotas/entrada:

- `/` → `LandingPage` no `App.tsx` (rota **pública**; o redirect antigo de `/`
  para `/dashboard` foi **removido** — a raiz é a landing; o `LoginPage` segue
  navegando para `/dashboard` após o login).
- `/` **já** está em `SecurityConfig.spaPublicPaths()` (Spec 46) e no fallback
  do `PortalSpaController` — F5/deep-link servem o `index.html` (sem o fix de
  rota pública que as Specs 80/84/142 precisaram).
- Todos os CTAs → `/register` (cadastro com API key 1x — Spec 142).

Identidade visual **escopada em `.landing`**: as CSS variables `--fox-*` e o
tema escuro + laranja-fox vivem só na landing (página pública standalone) — o
restante do portal segue o tema claro (Spec 46/73).

## Contexto (por que agora)

Buraco real identificado em 2026-08-16: o portal não tinha **landing page** nem
ponto de entrada de marketing — as rotas públicas eram só `/login`,
`/forgot-password`, `/reset-password` (e depois `/register`, Spec 142). O
backend `POST /api/v1/merchants` (Spec 4/12/39) já era `permitAll`, mas sem UI de
conversão. O **template visual** (protótipo "aaaaa", entregue pelo usuário em
2026-08-18) definiu a identidade (escura + laranja-fox, seções e copy) — esta
spec a aplica na stack atual do portal (Fase 29: CSS puro, sem Tailwind/Radix).

## Critérios de aceite

1. **Rota `/` pública** — `GET /` serve a SPA (200 `text/html` via
   `PortalSpaController`), **sem auth**; `/` em `spaPublicPaths()`.
2. **Seções do template** — hero (badge + título + subtítulo + CTA), métricas
   (4 cards), recursos (4 cards), como funciona (4 passos numerados),
   integrações (4 grupos), preços (card "Modelo transparente") e rodapé — com a
   copy do template.
3. **Header com estado** — deslogado: Login + "Criar conta"; logado (`useAuth`):
   "Painel" (`/dashboard`) + "Sair" (logout → `/login`); menu mobile
   (hambúrguer) colapsa a nav em telas estreitas.
4. **CTAs → cadastro** — "Criar conta gratuita" (hero e preços) e "Criar conta"
   (header/rodapé) apontam para `/register` (tela que exibe a API key 1x — Spec
   142); "Login" → `/login`.
5. **Identidade escopada** — tema escuro + laranja-fox somente em `.landing`
   (vars `--fox-*`); o tema claro do portal (auth/dashboard) não muda.
6. **Sem dependência nova** — ícones SVG inline; stack atual (React Router, CSS
   puro); zero mudança de contrato HTTP.
7. **Build** — `npm run build` do portal (tsc + vite) limpo; console rebuildado
   na sequência (ordem portal → console — achado Spec 73).
8. **E2E** — `e2e-tests/tests/landing.spec.js`: (a) `/` renderiza a landing com
   as seções; (b) header deslogado + CTA do hero → `/register`; (c) nav de
   âncoras + card de Preços.

## Dependências

- **Spec 46** (portal SPA) — `App.tsx`, `apiClient`, `SecurityConfig.spaPublicPaths`
  (o `/` já é público), `PortalSpaController`.
- **Spec 142/143** (cadastro) — `/register` com API key 1x e modal de termos
  (destino dos CTAs).
- **Spec 73** (UX) — padrão visual/a11y, `useDialogFocus` (não usado na landing,
  mas o padrão acessível é mantido: `aria-label`, `aria-expanded`, navs com
  `aria-label`).
- **Spec 63** (Turnstile) — via `/register` (a landing não tem formulário).

## Fora de escopo

- **Backend** — zero mudança de contrato HTTP; nenhum endpoint novo.
- **Restilizar login/cadastro** — a identidade nova é aplicada **só à landing**;
  as telas de auth seguem Spec 46/142 (os templates `.tmp-template/` são
  referência visual para evolução futura).
- **SEO/OG tags/SSR** — a SPA não tem SSR; título/descrição ficam para evolução
  (sem infra hoje).
- **Template final das páginas do menu** — `portal/.tmp-template/*` são
  placeholders de referência das Specs 101–119 (futuras), não desta spec.
- **Responsividade do restante do portal** — evolução futura (Spec 85–87
  discarded).

## Addendum — redesign visual fiel ao protótipo (2026-08-19)

Redesign **visual-only** da landing, aplicando com mais fidelidade o protótipo
"aaaaa" aprovado (o entregável de 2026-08-18 já tinha a identidade escura +
laranja-fox; este addendum refina a apresentação). **Nenhum texto/selo E2E
mudou** — a copy das seções e o contrato de rotas são os mesmos.

Mudanças em `portal/src/pages/LandingPage.tsx` + bloco `.landing-*` em
`portal/src/styles.css` (tudo escopado em `.landing`, vars `--fox-*`):

1. **Header em pill flutuante** — container `rounded-full` fixo com blur que
   encolhe ao rolar (`landing-header-scrolled`); nav + Login/Criar conta à
   direita; menu mobile preservado.
2. **Hero com mockup 3D** — coluna de copy + mockup do dashboard
   (`DashboardMockup`: faturamento + barras, conversão, bots ativos, últimas
   movimentações) com tilt `rotateY/rotateX`, animação `fox-float` e glow
   aurora atrás; badge com ícone sparkles e título com gradiente de texto.
3. **Métricas em painel único** — 4 células com divisores (em vez de 4 cards
   soltos).
4. **Recursos em bento grid** — card grande (Gateway Nativo com mini-card de
   saldo) + 2 médios (Fluxo Básico/Avançado) + fileira de 3 pequenos (Bot/PIX/
   Métricas); hover com glow + `translateY`.
5. **Como funciona** — passos com numeração outline (text-stroke), ícone em
   círculo, linha conectora no desktop e raposa ao fundo
   (`fox-forest-close.png`, watermark).
6. **Integrações** — bento 6 colunas (3/3/2/4) + **marquee** infinito de
   integrações (`fox-marquee`, com máscara de fade nas bordas).
7. **Preços** — card com **borda em gradiente** (`p-px`) e 2 tiles de valor.
8. **CTA final** — painel gradiente fox com a raposa ao fundo
   (`mix-blend-mode: luminosity` + máscara radial) e CTA inverso.
9. **Animações de entrada** — componente `Reveal` (IntersectionObserver,
   `fox-fade-up` com delays) + `prefers-reduced-motion` desliga tudo.

Assets: `portal/public/fox-forest-close.png` (usada em Como funciona e no CTA
final; copiada também `fox-snow-close.png` — não usada). O build do Vite copia
`public/` para o estático do Spring Boot automaticamente.

Gates (mesmos da spec): `npm run build` portal (83 módulos) + console (67,
ordem portal → console) limpos; E2E `e2e-tests/tests/landing.spec.js` **3/3**
sem ajuste de seletor (textos preservados). Rota `/`, `spaPublicPaths`, CTAs e
identidade escopada — inalterados.
