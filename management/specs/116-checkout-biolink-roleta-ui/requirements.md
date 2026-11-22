---
name: Checkout + Bio Link + Cupons & Sorteios — UI do portal
status: verified
references:
  - "management/vision.md#V84"
  - "management/specs/115-checkout-biolink-sorteio-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/48-stores-storefront/requirements.md"
---

# Spec 116 — Checkout + Bio Link + Cupons & Sorteios: UI do portal

## O que entrega

As páginas do portal para **Checkout** (listagem + construtor), **Bio Link**
(editor de árvore de links) e **Cupons & Sorteios** (config de prêmios) com a
descrição de produto do usuário (2026-08-15), mais as **páginas públicas**
`/c/:slug` e `/bio/:slug` (renderizadas pela SPA sobre os endpoints da Spec
115). Padrão UX da Spec 73.

1. **`/checkouts`**: KPIs globais (views, vendas, receita) + lista de páginas
   + botão "Criar checkout" → **construtor** (título, produto, preço, cores,
   logo, botão, sorteio) com preview.
2. **`/biolink`**: editor da árvore de links (título, avatar, bio, tema, itens
   com reorder).
3. **`/sorteio`** (ou dentro do construtor): config de prêmios (label +
   probabilidade, soma 100) + botão "Girar" (teste).
4. **Páginas públicas**: `/c/:slug` (renderiza o produto + gera o Pix via
   fluxo Spec 23; sorteio se ativa) e `/bio/:slug` (árvore de links) — rotas
   **públicas da SPA** (sem auth), adicionadas ao `SecurityConfig`/SPA.

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Checkouts, Bio Link e Sorteio
Hot. Backend: Spec 115 (criada junto).

## Critérios de aceite

1. **CheckoutsPage**: KPIs (views/vendas/receita — do `stats`/listagem),
   tabela de páginas (slug, produto, status, views), botão "Novo checkout" →
   modal/página de construtor com preview; ações (editar, ativar/desativar,
   copiar URL `/c/{slug}`, stats).
2. **Construtor**: campos (título, produto, preço, cores via color picker
   simples, logo URL, botão, toggle sorteio) + **preview** em tempo real (mini
   card do checkout) — validação (slug único 409 → banner).
3. **Biolink**: editor com itens (label/url) + **reorder** (sobe/desce ou
   drag simples) + link público `/bio/{slug}`.
4. **Sorteio**: config de prêmios (dinâmico — adicionar/remover linhas label +
   prob) com validação de soma 100; botão "Testar giro" → anima e mostra o
   prêmio (`POST spin`).
5. **Páginas públicas** `/c/:slug` e `/bio/:slug`: renderização simples (CSS
   puro), rota **fora do guard de auth** (pública — `SecurityConfig`
   permitAll + SPA public paths, padrão storefront Spec 48). A `/c/:slug`
   gera o Pix (reuso do `ChargePage` flow — Spec 23) e abre o pagamento.
6. **UX**: Spinner, empty states, banners (Spec 73), `ConfirmDialog` para
   desativar.
7. **Rotas/SPA**: `/checkouts`, `/biolink`, `/sorteio`, `/c/:slug`, `/bio/:slug`
   no `PortalSpaController` + `spaPublicPaths()` (as 2 públicas com permitAll).
8. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-checkout-ui.spec.js`) — criar checkout → preview → página pública
   → gerar Pix; biolink; sorteio spin; suíte completa sem regressão.

## Dependências

- **Spec 115** (backend) — endpoints + públicos.
- **Spec 73** — UX.
- **Spec 23** — fluxo de cobrança (página pública de checkout).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — o editor/checkout
  pertence ao contexto do perfil ativo (lente Bot/Gateway, Spec 124); a rota
  do checkout segue o perfil (Spec 122/139).

## Fora de escopo

- **Backend** — Spec 115.
- **Editor HTML/JS livre** — construtor de dados.
- **Desconto automático da sorteio no Pix** — evolução.
