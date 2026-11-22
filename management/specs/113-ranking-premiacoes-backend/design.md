---
name: Ranking + Premiações — concurso de faturamento e metas com recompensas (backend)
status: verified
---

# Spec 113 — Design: Ranking + Premiações (backend)

## D1 — Conceito

`RankingService` (leitura) + `RewardsService` (metas/progresso) + job
`RewardsScheduler`. O ranking é calculado ao vivo (Σ APPROVED — sem tabela de
snapshot); o progresso das metas usa `reward_progress` (V64) atualizada pelo
job.

```text
PortalRankingController (ROLE_MERCHANT_PORTAL)
   ├─ RankingService — posição + top10 (PeriodFilter Spec 105)
   └─ RewardsService — metas config + progresso (reward_progress)
        └─ RewardsScheduler (ShedLock + JobExecutionService) — recalcula, desbloqueia
```

## D2 — Migration V64

```sql
CREATE TABLE reward_progress (id UUID PK, merchant_id UUID NOT NULL REFERENCES merchants(id),
  reward_key VARCHAR(64) NOT NULL,          -- chave da meta (config)
  goal_amount NUMERIC(15,2) NOT NULL,
  progress_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  unlocked BOOLEAN NOT NULL DEFAULT FALSE,
  unlocked_at TIMESTAMPTZ NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (merchant_id, reward_key));
```

**V64** (V63 = Spec 111).

## D3 — Config (metas)

```yaml
foxcash:
  rewards:
    enabled: true
    cron: "0 15 4 * * *"          # diário 04:15 UTC
    tiers:                        # metas (progresso do mês corrente)
      - { key: "shark-blood", name: "Shark Blood", goal: 50000.00,
          description: "Meta 50k — premiação liberada" }
      - { key: "silver", name: "Prata", goal: 10000.00, description: "..." }
      # ... (defaults; config de plataforma)
```

`RewardsProperties` + binding contra o yml real (padrão Spec 68/L16).

## D4 — Endpoints

### D4.1 `GET /api/v1/portal/ranking?period=`

`{rank, totalPlayers, position, top10}` — query global: `GROUP BY merchant_id
+ SUM(amount) WHERE status=APPROVED AND created_at in range`, order desc, top
10; `position` = índice do merchantId autenticado no ranking completo (ou
`null` sem vendas).

### D4.2 `GET /api/v1/portal/rewards`

`[{key, name, goalAmount, description, unlocked, progressPercent, unlockedAt}]`
— progresso do lojista (mês corrente) vs metas da config; `reward_progress`
como cache do job (se desatualizado, o GET recalcula on-the-fly — decisão:
recalcular on-the-fly e atualizar, KISS).

## D5 — Job `foxcash-rewards`

ShedLock + `JobExecutionService` (Spec 53): para cada lojista ativo, recalcula
`progress_amount` (Σ APPROVED do mês); se `>= goal` e `!unlocked` → `unlocked`
+ `unlocked_at` + publica `REWARD_UNLOCKED` no outbox (Spec 35,
`REQUIRES_NEW`, fail-safe). Métrica `nyx_rewards_*`.

## D6 — Testes

**Unitários:** `RankingServiceTest` (períodos, posição, top10, sem vendas),
`RewardsServiceTest` (progresso, cap 100, unlock), `RewardsSchedulerTest`
(cruzou meta → unlocked + evento; idempotente). **Integração:** controllers.
**E2E** (`portal-ranking-rewards.spec.js`): 2 lojistas → ranking com posição →
meta baixa (test) → job → `unlocked` + evento.
