---
name: PgBouncer + pool tuning — destravar >1.550 vendas/s (ADR-013)
status: verified
---

# Spec 133 — Task: PgBouncer + pool tuning

Ordem: T0 (benchmark baseline) → T1 (estágio 1: pool) → T2 (estágio 2:
PgBouncer, se necessário) → T3 (zona sagrada) → T4 (docs/ADR).

## T0 — Baseline (critério C1)

- [ ] T0.1: Reproduzir o benchmark do capacity report §11.11 com a config
      atual (pool 100/réplica, 2→4→8→16 réplicas) — confirmar o baseline
      ~1.554/s em 8 e a degradação em 16.
- [ ] T0.2: Registrar métricas (throughput, p95, 5xx, duplicações) como
      ponto de comparação.

## T1 — Estágio 1: pool dimensionado (critérios C1, C2, C3)

- [x] T1.1: Calcular o pool por réplica para caber em `max_connections=500`
      com margem (ex.: 60) — cálculo registrado em `deploy/railway.md`
      (regra `pool ≤ floor(500/réplicas)`: 8 → 60×8=480; 16 → 30×16=480).
- [x] T1.2: `application.yml` — `NYX_DB_POOL_SIZE` por réplica já existe
      (`hikari.maximum-pool-size: ${NYX_DB_POOL_SIZE:20}` — linha 26);
      sem mudança de código necessária.
- [ ] T1.3: Benchmark 8 réplicas com o novo pool — **≥ 2× throughput de 4
      réplicas**; curva 2→4→8→16 linear ou melhor; ~3.000+ vendas/s em 16 se
      o pool permitir; 0% 5xx.
- [ ] T1.4: Decidir com evidência: se o estágio 1 provar escala suficiente,
      **PgBouncer dispensado** (registrar no ADR-013); senão, seguir para T2.

## T2 — Estágio 2: PgBouncer (se T1 não bastar; critérios C1, C3, C4)

- [ ] T2.1: Subir PgBouncer (transaction pooling) entre app e Postgres;
      config `pool_mode=transaction`, `server_reset_query`,
      `max_connections` ≤ 500 com margem.
- [ ] T2.2: Mitigação transaction pooling — prepared statements
      (desabilitar prepare / `max_prepared_statements`), `lastval`/`nextval`
      (confirmar `RETURNING id` no código), locks pessimistas (Specs 28/19 —
      transações curtas), ShedLock (Specs 6/53 — leases sobrevivem?).
- [ ] T2.3: Benchmark 16 réplicas com PgBouncer — ≥ 2× throughput de 8;
      ~3.000+ vendas/s; 0% 5xx; 0 duplicações.
- [ ] T2.4: Failover — Postgres down → erro limpo (sem hang); PgBouncer down →
      app não morre (retry/backoff do Hikari).

## T3 — Zona sagrada sob carga (critério C2)

- [ ] T3.1: Sob carga máxima — wallet×ledger 0/0, duplicações 0,
      reconciliation 0, deadlocks 0, outbox 0 FAILED (mesmas asserções da
      Spec 62 F9).

## T4 — Docs e ADR (critério C5)

- [x] T4.1: `deploy/railway.md` — **parte local concluída (2026-08-16)**: pool
      por réplica documentado (regra `pool ≤ floor(500/réplicas)`, ex. 60×8=480)
      + evidência F9 (800 > 500, degradação em 16) na seção de dimensionamento
      e na tabela de env vars. **Pendente:** o **novo número de capacidade
      medido** — depende do benchmark (T1.3, Railway).
- [ ] T4.2: ADR-013 atualizado — resultado dos estágios 1/2 com evidência
      (ADR-013 já registra a decisão revisada 2026-08-10; o resultado do
      benchmark fecha o registro — item 6 do plano da sessão).

**Nota de implementação local (2026-08-16):** o pool **já é configurável** via
`NYX_DB_POOL_SIZE` (application.yml — `hikari.maximum-pool-size:
${NYX_DB_POOL_SIZE:20}`); T1.2 não exige mudança de código — a config existe
(linha 26, não 19 — anchor atualizado). Os critérios C1/C2/C3/C4 dependem de
benchmark no Railway (T0/T1.3/T2/T3) — fora do escopo local desta sessão;
registrados no item 6 do plano (evidências benchmark 133).

**Âncoras validadas na spec:** `application.yml` linha 26 (Hikari
`NYX_DB_POOL_SIZE` — pool por réplica); ADR-013 linhas 419-469 (evidência F9 +
decisão revisada); `docs/capacity-report.md` §11.11 (metodologia); Specs 28/19
(locks), 6/53 (ShedLock), 60 (pool), 62 (capacity); `deploy/railway.md`.
