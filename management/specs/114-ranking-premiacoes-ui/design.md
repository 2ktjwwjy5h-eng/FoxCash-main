---
name: Ranking + Premiações — UI do portal
status: verified
---

# Spec 114 — Design: Ranking + Premiações (UI)

## D1 — Conceito

`RankingPage.tsx` (rota `/ranking`) e `RewardsPage.tsx` (rota `/rewards`).
Consumo da Spec 113; reuso de `KpiCard`/`ProgressBar` (Spec 104/73).

```text
portal/src/pages/RankingPage.tsx
portal/src/pages/RewardsPage.tsx
portal/src/components/RankingPodium.tsx
portal/src/components/RewardCard.tsx
```

## D2 — Ranking

`[Pódio top 3] / [Tabela 4-10] / [Card posição: #N de M] / [PeriodTabs]`.
- `RankingPodium`: cards top 3 com medalha (), nome, volume.
- Posição própria destacada (borda/cor) quando presente no top10.

## D3 — Premiações

`RewardCard`: nome, descrição, barra de progresso (`progressPercent`), badge
Liberada (`unlockedAt`) / Em andamento / Bloqueada (progresso 0).

## D4 — Testes

- `npm run build` (tsc) limpo no portal.
- E2E `portal-ranking-rewards-ui.spec.js`: pódio, posição, filtro período,
  progresso da meta, empty states.

## D5 — Wireframes aprofundados (registro de design 2026-08-16)

### D5.1 — Ranking (`/ranking`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Ranking                                        [Mês][Semana][Tudo]    │
│   PÓDIO (top 3 destacados)                                          │
│       ┌────────────┐                                                   │
│    ┌──┼──┐       │──┐    ┌────────────┐                              │
│    │    │ ┌───────┼──┼────┼──┐      │                              │
│    │ Loja B│ │ Loja A  │  │    │ Loja C  │                              │
│    │ R$8.5k│ │ R$9.999 │  │    │ R$7.2k  │                              │
│    └───────┘ └─────────┘  │    └─────────┘                              │
│                            └───────────────────┘                        │
│  ── Classificação (4–10) ──                                             │
│  ┌─────┬────────────┬──────────────┐   ┌────────────────────────────┐  │
│  │ #   │ Lojista    │ Faturamento  │   │  Sua posição               │  │
│  ├─────┼────────────┼──────────────┤   │   #2 de 48               │  │
+  │ 4   │ Loja D     │ R$ 6.100     │   │  Faturamento: R$ 8.500    │  │
│  │ 5   │ Loja E     │ R$ 5.400     │   │  (linha própria destacada  │  │
│  │ ... │            │              │   │   com borda quando no top) │  │
│  └─────┴────────────┴──────────────┘   └────────────────────────────┘  │
│                                                                        │
│   O Top 5 Players do Dashboard (Spec 104) reusa os MESMOS dados.     │
└────────────────────────────────────────────────────────────────────────┘
```

**Estados e interações:**
- **Loading**: `Spinner`; **erro**: `ErrorBanner` com `reload()`.
- **Empty**: "Nenhum dado de faturamento no período." (sem pódio; card de
  posição mostra "Sem posição no período").
- **Filtro período** (Mês/Semana/Tudo) → recarrega ranking (`?period=`).
- **Pódio**: top 3 com medalhas ; altura/largura do card do 1º maior
  (hierarquia visual); `aria-label` por posição.
- **Linha própria**: se o lojista estiver no top 10, a linha ganha borda de
  destaque + "você"; se estiver fora, o card lateral mostra `#N de M` com o
  volume (não na tabela).
- **Reuso**: `GET /api/v1/portal/ranking` (Spec 113) — mesmo endpoint que
  alimenta o Top 5 do dashboard.

### D5.1.1 — Pódio: composição visual e casos de borda (aprofundamento)

O pódio é o elemento mais "visual" do portal (CSS puro, Spec 73). Detalhamento:

```
          ┌─────────────────────────────────────┐
          │         MÊS DE AGOSTO             │
          │                                     │
          │        ┌───────────────┐            │
          │   ┌────┤  1º LUGAR    ├────┐      │
          │   │  │               │  │      │
          │   │2º  │  Loja A       │ 3º │      │
          │   │    │  R$ 9.999     │    │      │
          │   │    └───────────────┘    │      │
          │   │                         │      │
          │   │ Loja B                  │ Loja C│
          │   │ R$ 8.500                │ R$ 7.2k│
          └───┴─────────────────────────┴───────┘
          escala:   │▌ 2º = 0.85x   │▌ 1º = 1.0x   │▌ 3º = 0.7x
```

**Regras de composição (CSS puro, flexbox/grid):**
- **3 colunas**: 2º lugar à esquerda, 1º ao centro (maior — escala 1.0x),
  3º à direita (0.7x) — padrão de pódio (1º centralizado e destacado).
- **Medalhas**  com `role="img"` + `aria-label` (ex.: "1º lugar").
- **Nome + volume**: `formatMoney` completo no desktop; abreviação
  (`R$ 8,5k`) só abaixo de 480px.
- **Avatar** (iniciais) se disponível — reuso do padrão do shell (Spec 102
  `LeadAvatar`); sem foto → iniciais do nome.

**Casos de borda:**
- **Menos de 3 lojistas**: pódio renderiza só os que existem (1º sozinho →
  central; 2 → 1º + 2º); tabela e card de posição continuam.
- **Empate no volume**: a UI apenas espelha a ordem que o backend devolve
  (Spec 113 — desempate determinístico server-side); sem lógica de empate na UI.
- **Lojista no pódio**: card próprio ganha borda de destaque + selo "você"
  (mesmo padrão da linha da tabela).
- **Volume zero**: lojista com 0 no período não aparece no pódio (filtro no
  backend); card de posição mostra "Sem posição no período".
- **Acessibilidade**: pódio navegável por teclado (`tab` entre cards), com
  `aria-label` completo (posição, nome, volume) — não depende de cor sozinha.

### D5.2 — Premiações (`/rewards`)

```
┌────────────────────────────────────────────────────────────────────────┐
│  Premiações                                                           │
│  ┌─────────────────────────┐  ┌─────────────────────────┐             │
│  │  SHARK BLOOD          │  │  META DE 100 VENDAS   │             │
│  │ R$ 50.000/mês           │  │                        │             │
│  │ ████████████░░░░ 72%    │  │ ████████████████ 100%  │             │
│  │ R$ 36.000 / R$ 50.000   │  │ [LIBERADA 16/08]    │             │
│  │ [Em andamento]          │  │                        │             │
│  └─────────────────────────┘  └─────────────────────────┘             │
│  ┌─────────────────────────┐                                          │
│  │  NOVA FASE            │                                          │
│  │ R$ 100.000/mês          │                                          │
│  │ ░░░░░░░░░░░░░░ 0%       │                                          │
│  │ [Bloqueada]             │                                          │
│  └─────────────────────────┘                                          │
└────────────────────────────────────────────────────────────────────────┘
```

**Estados e interações:**
- **Loading**: `Spinner`; **erro**: `ErrorBanner` com `reload()`.
- **Empty**: "Nenhuma premiação configurada ainda." (sem grade).
- **RewardCard**: nome, descrição (meta), barra de progresso
  (`progressPercent` — reuso da barra da Spec 104), valores
  `{atingido} / {meta}` e badge de estado:
  - `[LIBERADA {data}]` (tag-success) quando `unlockedAt` presente;
  - `[Em andamento]` (tag-warning) com progresso > 0;
  - `[Bloqueada]` (tag-neutral) com progresso 0.
- **Config**: metas vem de `GET /api/v1/portal/rewards` (Spec 113 —
  `foxcash.rewards.*`); sem edição pelo lojista (config do master/operador).
- **Dashboard**: a seção "Premiações/Metas" do Dashboard rico (Spec 104 D5)
  renderiza os mesmos `RewardCard` em formato compacto.
