---
name: PgBouncer + pool tuning — destravar >1.550 vendas/s (ADR-013)
status: verified
---

# Spec 133 — Design: PgBouncer + pool tuning (escala além de 1.550 vendas/s)

## D1 — Conceito

O capacity report (Spec 62 §11.11) **provou o gargalo**: scale-out para de
escalar em ~1.550 vendas/s (8 réplicas) porque o **Postgres max_connections=500**
é o teto — pool Hikari 100/réplica × 8 réplicas deseja 800 conexões
(`too many clients already`); 16 réplicas degrada (sale_ok 94-97%, p95 ~10-19s).
ADR-013 (atualização 2026-08-10) registrou duas correções candidatas: (a)
**dimensionar o pool Hikari por réplica** (ex.: 60 × 8 = 480 < 500, sem infra
nova, reversível) e (b) **PgBouncer** (transaction pooling) se scale-out além de
~8 réplicas for necessário. Esta spec implementa **com evidência** (regra de
ouro da Spec 62).

## D2 — Decisão em 2 estágios (C1)

**Estágio 1 — pool dimensionado (imediato, sem infra nova):**
- `application.yml` linha 19: `maximum-pool-size: ${NYX_DB_POOL_SIZE:20}` →
  valor por réplica **calculado pelo benchmark** (ex.: 60) para caber em
  `max_connections` com margem (60 × 8 = 480 < 500; 60 × 16 = 960 > 500 — por
  isso o estágio 2 existe).
- Rodar o benchmark (metodologia do capacity report §11.11) em 8 réplicas com
  o novo pool: confirmar **≥ 2× throughput de 4 réplicas** e melhorar a curva
  atual (8 → ~1.554/s não linear).

**Estágio 2 — PgBouncer (transaction pooling) se o estágio 1 não bastar:**
- Introduzir PgBouncer entre app e Postgres (container/sidecar no Railway ou
  serviço dedicado). `pool_mode = transaction`; `max_connections` do PgBouncer
  dimensionado para o Postgres (500) com margem; `server_reset_query` para
  reset de sessão entre transações (necessário em transaction pooling).
- **Mitigação dos riscos do transaction pooling (C3)** — verificar cada um com
  teste de integração sob carga:
  - **Prepared statements**: Hikari com `maximumPoolSize` + JPA/JDBC prepare —
    desabilitar prepare (ou `max_prepared_statements`) no caminho do PgBouncer;
  - **`lastval`/`nextval` sequences**: session-scoped — com pooling
    transacional o `nextval` é por conexão; confirmar que o app não depende de
    `lastval` entre statements (usar `RETURNING id` — verificar o código);
  - **Locks pessimistas** (`SELECT ... FOR UPDATE`, Specs 28/19): transação
    curta é OK em transaction pooling; transações longas (não vistas no caminho
    da venda — Spec 28) seriam problema;
  - **ShedLock** (Specs 6/53): usa locks de banco com leases — verificar se
    sobrevive ao pooling transacional (se não, mover para standalone).

**Se o benchmark do estágio 1 provar escala linear 8→16 (≥ 3.000/s, 0% 5xx),
PgBouncer fica dispensado (mesmo critério do ADR-013)** — a decisão é fechada
com evidência, nunca por preferência.

## D3 — Zona sagrada sob carga (C2)

Mesmo critério da Spec 62 F9: sob carga máxima — wallet×ledger 0/0,
duplicações 0, reconciliation 0, deadlocks 0, outbox 0 FAILED. Benchmark roda
com as mesmas asserções do capacity report.

## D4 — Failover (C4)

- PgBouncer up, Postgres down → erro limpo (sem hang); health reflete.
- PgBouncer down → app não morre: Hikari com retry/backoff curto na aquisição
  de conexão (não crash de pool) + health do datasource.

## D5 — Configuração documentada (C5)

- `deploy/railway.md`: pool size por réplica (estágio 1), config do PgBouncer
  (max_connections, pool_mode, server_reset_query), JAVA_OPTS se mudar, e o
  **novo número de capacidade medido** (nunca estimado — diretriz 2026-08-08).
- ADR-013 atualizado: resultado do estágio 1 (e 2, se aplicado) com evidência.

## D6 — Arquivos

| Arquivo | Mudança |
|---|---|
| `src/main/resources/application.yml` | `NYX_DB_POOL_SIZE` por réplica (D2 est. 1) |
| `deploy/railway.md` | pool, PgBouncer (se aplicável), capacidade medida (D5) |
| `management/adr.md` (ADR-013) | atualização com evidência do benchmark (D5) |
| (se estágio 2) infra PgBouncer | serviço/config no Railway + mitigação prepared/sequences/locks |

## D7 — Fora de escopo

Multi-região/DR (Spec 135); fila dedicada (Spec 134, independente do pool);
réplicas de leitura (Spec 62/ADR-013 — permanece dispensado até evidência).

**Âncoras validadas na spec:** `application.yml` linha 19 (`maximum-pool-size:
${NYX_DB_POOL_SIZE:20}`); ADR-013 (linhas 419-469 — evidência F9: 8 réplicas
~1.554/s, 800 conexões > 500, decisão revisada); `docs/capacity-report.md`
§11.11 (metodologia e baseline); `deploy/railway.md` (docs de deploy); Specs
28/19 (locks), 6/53 (ShedLock), 60 (pool atual).
