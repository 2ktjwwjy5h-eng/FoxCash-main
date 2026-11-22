---
name: Ranking + Premiações — UI do portal
status: verified
references:
  - "management/vision.md#V83"
  - "management/specs/113-ranking-premiacoes-backend/requirements.md"
  - "management/specs/73-frontend-ux/requirements.md"
  - "management/specs/104-dashboard-rico-ui/requirements.md"
---

# Spec 114 — Ranking + Premiações: UI do portal

## O que entrega

Páginas **Ranking** (rota `/ranking`) e **Premiações** (rota `/rewards`) no
portal com a descrição de produto do usuário (2026-08-15). Puramente
apresentação sobre os endpoints da Spec 113. Padrão UX da Spec 73.

1. **Ranking**: Top 10 com posição, nome e faturamento; posição do lojista
   destacada; filtro de período (Mês/Semana/Tudo). O **Top 5 Players** do
   dashboard (Spec 104) reusa os dados.
2. **Premiações**: cards de metas/placas com progresso (barra), nome
   (ex.: "Shark Blood"), descrição, estado (Bloqueada/Liberada com data).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Ranking (concurso) e Premiações
(placas/recompensas). Backend: Spec 113 (criada junto).

## Critérios de aceite

1. **RankingPage**: tabela/list com `top10` (`{rank, merchantName,
   approvedVolume}`) + card "Sua posição: #N de M" + filtro `period`
   (Mês/Semana/Tudo). Pódio visual (top 3 destacados).
2. **RewardsPage**: grade de metas — nome, descrição, barra de progresso
   (`progressPercent`), badge "Liberada" (com `unlockedAt`) ou "Em
   andamento"/"Bloqueada".
3. **UX**: Spinner, empty states, banners (Spec 73).
4. **Rotas/SPA**: `/ranking`, `/rewards` no `PortalSpaController` +
   `spaPublicPaths()` (lição Specs 80/84).
5. **Testes**: `npm run build` (tsc) limpo no portal + E2E
   (`portal-ranking-rewards-ui.spec.js`) — pódio, posição, progresso de meta;
   suíte completa sem regressão.

## Dependências

- **Spec 113** (backend).
- **Spec 73** (UX).
- **Spec 102** (menu — Ranking/Premiações no grupo Visão Geral).
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — dados por perfil
  ativo (Spec 124); respeitar a lente Bot/Gateway.

## Fora de escopo

- **Backend** — Spec 113.
- **Envio físico de premiação** — operacional.
