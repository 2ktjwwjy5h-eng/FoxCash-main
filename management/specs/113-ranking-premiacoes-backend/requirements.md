---
name: Ranking + Premiações — concurso de faturamento e metas com recompensas (backend)
status: verified
references:
  - "management/vision.md#V83"
  - "management/specs/30-wallet-reconciliation/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/103-dashboard-rico-backend/requirements.md"
---

# Spec 113 — Ranking + Premiações: concurso de faturamento e metas (backend)

## O que entrega

Backend do **Concurso de Faturamento** e das **Premiações/Metas** do portal
(descrição de produto do usuário 2026-08-15): competição interna de vendas
entre lojistas com ranking e metas que liberam recompensas (ex.: meta de 50k
libera o card "Shark Blood").

1. **Ranking** (`GET /api/v1/portal/ranking?period=mes|semana|todo`):
   `{rank, totalPlayers, position, top10: [{rank, merchantName,
   approvedVolume}]}` — faturamento aprovado (Σ APPROVED, mesmo critério do
   Spec 30/103) por lojista, ordenado desc. O **Top 5 Players** do dashboard
   (Spec 103) reusa este endpoint (limit 5).
2. **Premiações/Metas** (`GET /api/v1/portal/rewards`): metas configuráveis
   `foxcash.rewards.*` — `[{id, name, goalAmount, description, unlocked,
   progressPercent, unlockedAt?}]`. Ex.: meta 50k → "Shark Blood".
3. **Job de verificação de metas** (ShedLock + `JobExecutionService` — Spec
   53): quando o faturamento do lojista atinge a meta, marca `unlocked` +
   `unlocked_at` (tabela `reward_progress` V64) e publica evento no outbox
   (alerta ao lojista/operador — padrão Spec 35).

## Contexto (por que agora)

Descrição de produto do usuário (2026-08-15) — Ranking (concurso de
faturamento) e Premiações (placas/recompensas por metas). Lacuna: nada existe
no backend. Os dados de faturamento já existem (Σ APPROVED).

## Critérios de aceite

1. **`GET /api/v1/portal/ranking?period=`** → `{rank, totalPlayers, position,
   top10}`. Períodos: `mes` (default), `semana`, `todo` (UTC — mesmo
   `PeriodFilter` da Spec 105). `position` = posição do lojista autenticado;
   `top10` = Top 10 (o dashboard usa limit 5). `approvedVolume` com `Money.
   normalize` (Spec 30). Nome do lojista (nunca e-mail).
2. **`GET /api/v1/portal/rewards`** → `[{id, name, goalAmount, description,
   unlocked, progressPercent, unlockedAt}]` — progresso = `Σ APPROVED do mês ÷
   goalAmount × 100` (cap 100). Metas da config `foxcash.rewards.tiers`
   (lista de `{name, goal, description}` — default: ex. 10k/25k/50k/100k com
   nomes).
3. **`reward_progress`** (V64): `id, merchant_id, reward_id (índice/config),
   goal_amount, progress_amount, unlocked BOOLEAN, unlocked_at, updated_at` —
   snapshot do progresso por lojista/meta (idempotente; 1 linha por
   lojista+meta).
4. **Job `foxcash-rewards`** (ShedLock + `JobExecutionService`, cron diário
   configurável `foxcash.rewards.cron`, kill-switch `foxcash.rewards.enabled`):
   recalcula o progresso de todos os lojistas; ao cruzar a meta → `unlocked`
   + evento `REWARD_UNLOCKED` no outbox (Spec 35, `REQUIRES_NEW`, fail-safe).
5. **Sem PII em logs**; rate limit `/api/v1/portal/**` (Spec 58); contratos
   existentes inalterados.
6. **Testes**: unitários + integração + E2E; `mvn test` verde.

## Dependências

- **Spec 30** — somas APPROVED agregadas.
- **Spec 53** — job com `JobExecutionService`/ShedLock.
- **Spec 35** — outbox para o `REWARD_UNLOCKED`.
- **Spec 103** — Top 5 reusa o ranking.
- **Spec 139** (escopo + lente, `in-progress` 2026-08-16) — somas APPROVED
  escopadas por `profile_id` (Spec 124/70): Top 10 e posição do lojista por
  perfil ativo; respeitar a lente Bot/Gateway.

## Fora de escopo

- **UI** — Spec 114.
- **Recompensas físicas reais (envio)** — o job só marca `unlocked` + alerta;
  o envio físico é operacional (evolução).
- **Config da meta pelo lojista** — metas são config da plataforma.
