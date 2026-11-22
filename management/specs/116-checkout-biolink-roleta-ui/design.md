---
name: Checkout + Bio Link + Cupons & Sorteios — UI do portal
status: verified
---

# Spec 116 — Design: Checkout + Bio Link + Sorteio (UI)

## D1 — Conceito

Páginas do portal (checkouts/biolink/sorteio) + **rotas públicas** da SPA
(`/c/:slug`, `/bio/:slug`) sobre os endpoints da Spec 115. Editor é
**construtor de dados** com preview.

```text
portal/src/pages/CheckoutsPage.tsx      → listagem + KPIs
portal/src/pages/CheckoutBuilderPage.tsx → construtor + preview
portal/src/pages/BioLinkPage.tsx        → editor de árvore
portal/src/pages/SorteioPage.tsx         → config de prêmios
portal/src/pages/public/PublicCheckout.tsx → /c/:slug (público)
portal/src/pages/public/PublicBio.tsx     → /bio/:slug (público)
```

## D2 — Checkout builder

- Formulário + `CheckoutPreview` (mini renderização com cores/produto/botão).
- **Sorteio toggle**: abre a seção de prêmios (reuso do `SorteioPage`).
- Salvar → `POST/PATCH /portal/checkouts` → mostrar URL pública
  `{base}/c/{slug}` + Copiar (Spec 73).

## D3 — Página pública `/c/:slug`

- `PublicCheckout` (rota pública, sem auth): consome
  `GET /api/v1/public/checkout/{slug}` (Spec 115) + gera o Pix via o fluxo da
  cobrança (reuso `POST /api/v1/foxcash/pix/cob` — mas com credencial do lojista
  em contexto server-side; decisão: o backend expõe `POST
  /api/v1/public/checkout/{slug}/charge` que gera o Pix com a credencial do
  dono do checkout — aditivo da Spec 115, implementação na 116 documenta).
- Sorteio ativa → roda e mostra o prêmio.

## D4 — Página pública `/bio/:slug`

- `PublicBio`: avatar, bio, itens como links grandes (árvore) — `GET
  /api/v1/public/bio/{slug}`.

## D5 — Rotas públicas no SecurityConfig

`/c/**` e `/bio/**` (SPA) + `/api/v1/public/**` (backend) em `permitAll` +
`spaPublicPaths()` — padrão storefront Spec 48 (sem auth).

## D6 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-checkout-ui.spec.js`: criar checkout → preview → URL pública →
  página `/c/{slug}` (sem login) gera Pix → biolink público → sorteio spin;
  suíte completa sem regressão.

## D6.1 — Não ignorar a Spec 139 (contexto do perfil)

O editor/checkout pertence ao contexto do perfil ativo (lente Bot/Gateway, Spec
139). A rota do checkout gerado segue o perfil do lojista (Spec 122/139); o
preview/estado não deve assumir rota global.

## D7 — Wireframes do Gateway e do Bio Link (registro de design 2026-08-16)

Visual de referência das **páginas públicas de cliente** (ADR-020 — superfície
"Gateway"), aprovado no detalhamento do Gap 4.

### D7.1 — Checkout público `/c/:slug` — estados

**Estado [1] Loading / [2] Produto pronto:**

```
┌──────────────────────────────────────────────┐
│                 (logo_url)                   │
│  ┌────────────────────────────────────────┐  │
│  │               PREMIUM               │  │   ← product_name
│  │             R$ 49,90                   │  │   ← product_price
│  │   ✓ Acesso imediato                    │  │
│  │   ✓ Suporte VIP                        │  │
│  │        ┌────────────────────┐          │  │
│  │        │   Girar a sorteio  │          │  │   ← se sorteio_enabled
│  │        │  [GIRAR]           │          │  │
│  │        └────────────────────┘          │  │
│  │  ┌──────────────────────────────────┐  │  │
│  │  │  [COMPRAR AGORA — R$ 49,90 ] │  │  │   ← button_text (cor button)
│  │  └──────────────────────────────────┘  │  │
│  └────────────────────────────────────────┘  │
│  [Pagamento processado pela FoxCash]      │
└──────────────────────────────────────────────┘
```

**Estado [2b] Sorteio girada** (spin server-side, prêmio informativo nesta fase):

```
│   PARABÉNS!   ┌───────────────┐
│                 │   10% OFF   │
│                 └───────────────┘
│  Você ganhou: **10% OFF**
│  [COMPRAR AGORA — R$ 49,90 ]
```

**Estado [3] Cobrança gerada** (pós `charge` — Spec 115 D8):

```
│  Pague com PIX — PREMIUM          R$ 49,90
│        ┌──────────────────┐
│        │     QR CODE      │      ← pixQrCode (grande, central)
│        └──────────────────┘
│  [COPIAR COPIA E COLA ]      ← copyToClipboard + "Copiado!"
│  Tempo: Expira em **14:59**           ← countdown (expirationSeconds)
│  status: AGUARDANDO PAGAMENTO
│  [JÁ PAGUEI — CONFERIR ]      ← polling curto (espelha o webhook)
```

**Estado [4] Confirmado** (reflexo do webhook SALE_PAID):

```
│              
│   PAGAMENTO CONFIRMADO!
│    Chave de acesso:  XXXX-XXXX
│    Entrega enviada automaticamente
│   Produto:  PREMIUM · Referência: TREVO-...
│  [Voltar para a loja]
```

**Estado de erro** (404/inativo/429): tela limpa "checkout não encontrado" +
botão voltar; **sem vazar informação**.

### D7.2 — Bio Link público `/bio/:slug`

```
┌──────────────────────────────────────────────────────────────┐
│                    ( avatar_url — círculo )                  │
│                    [title — nome da página]                  │
│              [bio — texto livre até 500 chars]               │
│  ┌────────────────────────────────────────────────────────┐  │
│  │    COMPRAR AGORA                                     │  │
│  └────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐  │
│  │    CHAMAR NO WHATSAPP                                │  │
│  └────────────────────────────────────────────────────────┘  │
│  ...  (items[] ordenados por sort — botões grandes, touch)   │
│                 (rodapé — tema/cor)                          │
└──────────────────────────────────────────────────────────────┘
```

### D7.3 — Construtor `/checkouts/new` (2 colunas: form + preview ao vivo)

```
┌──────────────────────────────┬─────────────────────────────────┐
│  CONFIGURAÇÃO                │  PREVIEW AO VIVO                │
│  Título         [Lancamento] │  ┌───────────────────────────┐  │
│  Slug           [lancamento] │  │      (logo)               │  │
│  Produto        [PREMIUM]    │  │   PREMIUM               │  │
│  Preço (R$)     [49,90]      │  │  R$ 49,90                 │  │
│  Logo URL       [https://..] │  │   Sorteio: [GIRAR]       │  │
│  Cor primária   [#0F2A3C] │  │  ┌─────────────────────┐  │  │
│  Cor do botão   [#E6007E] │  │  │ [COMPRAR R$ 49,90] │  │  │
│  Texto do botão [COMPRAR]    │  │  └─────────────────────┘  │  │
│   Cupons & Sorteios    [x] ativar │  │  [FoxCash]            │  │
│  └─ Prêmios (soma 100):      │  └───────────────────────────┘  │
│     10% OFF [30] · Brinde[50]│                                 │
│     Tente de novo [20]  Σ100│                                 │
│  [Salvar checkout]        │                                 │
└──────────────────────────────┴─────────────────────────────────┘
→ Salvar → banner + URL pública `/c/{slug}` + [Copiar] (Spec 73)
```

### D7.4 — Listagem `/checkouts` + Sorteio `/sorteio`

- **Listagem**: KPIs (views/vendas/receita) + tabela (slug, produto, status,
  views, ações: Editar/Pausar-Ativar/Copiar URL/Stats) + empty state com CTA.
- **Sorteio**: linhas dinâmicas `label + probability` com **Σ = 100** (verde ;
  botão salvar travado se ≠ 100) + botão "Testar giro" → `POST spin`
  (server-side, peso aleatório).

### D7.3.1 — Construtor: preview ao vivo, validações e slug (aprofundamento)

O construtor é o maior formulário do portal (2 colunas). Detalhamento:

**Preview ao vivo — o que reflete em tempo real (sem submit):**
```
┌──────────────────────────────┬─────────────────────────────────┐
│  CONFIGURAÇÃO                │  PREVIEW AO VIVO                │
│  Título [Lancamento]         │  ┌───────────────────────────┐  │
│  Produto [PREMIUM]           │  │ (logo_url)               │  │
│  Preço   [49,90]             │  │  PREMIUM               │  │
│  Logo    [https://...]       │  │ R$ 49,90   ← atualiza a  │  │
│  Cor prim [#0F2A3C]       │  │   cada keystroke         │  │
│  Cor botão[#E6007E]       │  │ ┌─────────────────────┐  │  │
│  Botão   [COMPRAR]           │  │ │ [COMPRAR R$ 49,90] │  │  │
│                              │  │ │ cor do botão vive   │  │  │
│  Sorteio   [x]                │  │ │ do picker           │  │  │
│  └─ prêmios (Σ=100)          │  │ └─────────────────────┘  │  │
│                              │  │ [FoxCash]            │  │
│  [Salvar]  [Cancelar]     │  └───────────────────────────┘  │
└──────────────────────────────┴─────────────────────────────────┘
```
- **Campos reativos**: `productName`, `productPrice`, `logoUrl`, `colors.
  primary`, `colors.button`, `buttonText`, `sorteio_enabled` — cada mudança
  atualiza o preview (estado local, re-render direto).
- **Logo ausente**: preview mostra placeholder circular com iniciais do título.
- **Sorteio off**: preview não exibe a seção de giro.
- **Preço inválido/0**: preview mostra o botão sem valor (`[COMPRAR]`) e a
  borda do campo fica vermelha.

**Validações e feedback (Spec 73):**
| Campo | Regra | Feedback |
|---|---|---|
| Título | 1–120, obrigatório | erro inline no blur |
| Slug | 1–64, `[a-z0-9-]`, obrigatório | erro inline; **slug sugerido** automático do título (ex.: "Lancamento" → `lancamento`) |
| Produto | 1–120, obrigatório | erro inline |
| Preço | > 0 (até 2 casas) | erro inline |
| Cores | `#RRGGBB` | picker restringe; erro se colado inválido |
| Logo URL | opcional, URL válida (SSRF-safe) | erro inline se inválida |
| Texto botão | ≤ 60 | contador de chars |
| Sorteio | Σ probabilidades = 100 | bloco da sorteio: verde  ou vermelho + salvar travado |

**Slug: interação e 409:**
- Campo slug com botão " gerar do título" (normaliza: lowercase, remove
  acentos/espaços → `lancamento-2026`).
- Salvar com slug repetido → **409 `slug_already_exists`** → `ErrorBanner` no
  topo + foco no campo slug + sufixo sugerido `-2` (o lojista decide — não
  auto-aceita).
- **Botão Salvar**: `busy` com "Salvando…"; sucesso → `SuccessBanner` + bloco
  final com a URL pública `/c/{slug}` + `[Copiar]` (Spec 73) + `[↗ Abrir]`.

**Persistência e sorteio (Spec 115):**
- Salvar dispara `POST /portal/checkouts` (criar) ou `PATCH /portal/checkouts/
  {id}` (editar); se `sorteio_enabled`, envia também `sorteio_config` (ou cria a
  config vinculada — decisão de implementação da Spec 115).
- Edição de checkout **com stats/views existentes**: preserva `views` (o
  backend não zera — contador acumulado; a UI avisa "o histórico de views é
  mantido").

### D7.2.1 — Editor do Bio Link (`/biolink` — aprofundamento)

A página pública (`/bio/:slug`) é renderizada a partir do que este editor
configura. Wireframe do editor:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Bio Link                                        [Ver público]      │
│  ┌────────────────────────────┬───────────────────────────────────────┐ │
│  │  IDENTIDADE                │  PREVIEW AO VIVO (mini)               │ │
│  │  Título      [Meu linktree]│   ┌───────────────────────────────┐   │ │
│  │  Slug        [trevosneaker]│   │        (avatar)               │   │ │
│  │  Avatar URL  [https://...] │   │       Meu linktree            │   │ │
│  │  Bio         [Aqui está tu-│   │    COMPRAR AGORA            │   │ │
│  │              do…] (≤500)  │   │    WHATSAPP                 │   │ │
│  │  Tema        [#0F2A3C] │   │    INSTAGRAM                │   │ │
│  │  Status      [● ATIVO]     │   └───────────────────────────────┘   │ │
│  │  [Salvar identidade]    │   (atualiza a cada keystroke)         │ │
│  └────────────────────────────┴───────────────────────────────────────┘ │
│                                                                        │
│  ── ITENS DA ÁRVORE ──                                    [+ Adicionar]│
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ ≡  COMPRAR AGORA   → https://trevo.app/c/lancamento   [↑][↓][ed][rem]│
│  │ ≡  WHATSAPP       → https://wa.me/5511999999999      [↑][↓][ed][rem]│
│  │ ≡  INSTAGRAM      → https://instagram.com/trevo       [↑][↓][ed][rem]│
│  └──────────────────────────────────────────────────────────────────┘  │
│  URL pública: {base}/bio/trevosneaker   [Copiar]   Copiado!       │
└────────────────────────────────────────────────────────────────────────┘
```

**Modal Adicionar/Editar item:**
```
┌──────────────────────────────────────────────────────────────┐
│  Item da árvore                                     [x]     │
│  Rótulo  [COMPRAR AGORA]   (1–120, emoji permitido)       │
│  URL     [https://trevo.app/c/lancamento]  (URL — SSRF-safe) │
│  ───────────────────────────────────────────                 │
│  [Salvar]  [Cancelar]                                       │
└──────────────────────────────────────────────────────────────┘
```

**Regras e interações:**
- **Reorder**: ↑/↓ (persistência `PUT /biolinks/{id}/order {ids[]}` — Spec 115
  D3); ↑ desabilitado no 1º, ↓ no último; preview reflete a ordem.
- **Item**: label 1–120 (emoji/ícone no início do rótulo é permitido — o botão
  da página pública renderiza `{label}` inteiro), URL validada
  (SSRF-safe, reuso `WebhookUrlValidator`).
- **Slug**: gerado do título (mesmo padrão do construtor de checkout, D7.3.1);
  409 `slug_already_exists` → banner + sufixo sugerido.
- **Avatar**: URL (img `referrerpolicy="no-referrer"`); ausente → iniciais do
  título (padrão shell Spec 102).
- **Tema**: picker `#RRGGBB` → aplica no preview (fundo/acento) e na página
  pública.
- **Ver público**: abre `/bio/{slug}` em nova aba; **Copiar URL** com feedback
  (Spec 73).
- **Pausar/Ativar**: na seção Status (ConfirmDialog para pausar — efeito
  visível na página pública).
- **Empty**: "Nenhum item ainda — adicione o primeiro link." (o preview mostra
  só identidade).

### D7.4.1 — Página da Sorteio (`/sorteio` — aprofundamento)

Configuração de prêmios (pode ser acessada de `/sorteio` ou dentro do construtor
de checkout, D7.3). Wireframe completo:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Cupons & Sorteios                                          [Testar giro]  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Prêmio               Probabilidade (%)   Remover                │  │
│  │  ─────────────────────────────────────────────────────────────   │  │
│  │  [10% OFF            ]  [ 30 ]             [rem]               │  │
│  │  [Brinde surpresa    ]  [ 50 ]             [rem]               │  │
│  │  [Tente novamente    ]  [ 20 ]             [rem]               │  │
│  │  [+ Adicionar prêmio]                                           │  │
│  │  ─────────────────────────────────────────────────────────────   │  │
│  │  Soma:  100%    válida   (verde)                                │  │
│  │          (se ≠ 100 → 87%  inválida — vermelho, salvar travado)  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                        │
│  ┌─ Resultado do teste ─────────────────────────────────────────────┐  │
│  │   Girando… →  " Você ganhou: 10% OFF!"                       │  │
│  │  (POST /portal/sorteios/{id}/spin — server-side, peso aleatório)  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  [Salvar configuração]                                              │
└────────────────────────────────────────────────────────────────────────┘
```

**Regras e interações:**
- **Linhas dinâmicas**: `label` (1–120) + `probability` (número inteiro 0–100);
  adicionar/remover linhas (`[+]`/`[rem]`); mínimo 1 prêmio (100%).
- **Σ = 100**: indicador sempre visível — verde  quando soma exatamente 100
  (botão salvar habilitado); vermelho  com o valor atual quando ≠ 100
  (salvar desabilitado). Backend também valida (`400 invalid_argument`).
- **Testar giro**: `POST spin` real (server-side) → anima (CSS puro) → mostra o
  prêmio sorteado. Não manipula probabilidades (Spec 115 D5).
- **Vínculo**: a config pode ser **global** ou por checkout (`checkout_page_id`
  nullable — Spec 115); o `/sorteio` mostra o vínculo ("Aplicada a: Todos os
  checkouts" ou "Aplicada a: /c/lancamento").
- **Empty**: "Nenhum prêmio configurado." + CTA `[+ Adicionar primeiro
  prêmio]` (começa com 1 linha 100%).
- **KISS**: sem animação de sorteio complexa (CSS keyframes simples); o prêmio é
  **informativo** nesta fase (desconto real = evolução).
