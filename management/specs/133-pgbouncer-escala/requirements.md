---
name: PgBouncer + pool tuning — destravar >1.550 vendas/s (ADR-013)
status: verified
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/62-capacity-engineering-high-throughput/requirements.md"
  - "management/adr.md#ADR-013"
---

# Spec 133 — PgBouncer + pool tuning: escala além de 1.550 vendas/s

## O que entrega

O capacity report (Spec 62, 2026-08-10) provou: **scale-out para de escalar em
~1.550 vendas/s (8 réplicas)** porque o **Postgres max_connections=500** é o
gargalo central — com pool Hikari 100/réplica, 8 réplicas desejam 800 conexões
(`too many clients already`). A decisão ADR-013 registrou duas correções
candidatas: (a) reduzir pool por réplica ao escalar, (b) **PgBouncer**
(transaction pooling) para desacoplar conexões da app do limite do Postgres.

Esta spec implementa a solução com evidência (regra de ouro da Spec 62: nunca
alterar arquitetura sem provar o gargalo — o gargalo JÁ está provado e medido).

## Critérios de aceite

**C1 — Decisão com benchmark.** Executar benchmark (metodologia do capacity
report §11.11) com PgBouncer (ou pool reduzido por réplica) em 8 e 16 réplicas.
A curva 2→4→8→16 deve ser **linear ou melhor** (16 réplicas ≥ 2× o throughput
de 8 réplicas, ~3.000+ vendas/s sustentadas, 0% 5xx, 0 duplicações). Se não
melhorar, a decisão volta para revisão com evidência.

**C2 — Zona sagrada intacta.** Sob carga máxima: wallet×ledger 0/0, duplicações
0, reconciliation 0, deadlocks 0, outbox 0 FAILED — mesmo com o Postgres no
limite (mesmo critério da Spec 62 F9).

**C3 — Transacional com PgBouncer.** PgBouncer em **transaction pooling** não
quebra: prepared statements (modo `transaction` exige `server_reset_query`/
`max_prepared_statements` ou desabilitar prepare), `lastval`/`nextval`
sequences, locks pessimistas (`SELECT ... FOR UPDATE` — Spec 28/19) e
`ShedLock` (Spec 6/53). Verificar cada um com teste de integração sob carga.

**C4 — Failover.** Com PgBouncer entre a app e o Postgres: health continua
funcionando; se o Postgres cair, o erro é limpo (sem hang da app); se o
PgBouncer cair, a app não morre (retry/backoff).

**C5 — Configuração documentada.** `deploy/railway.md` + ADR-013 atualizados:
pool size por réplica, PgBouncer (max_connections, pool_mode, server_reset_query),
JAVA_OPTS se mudar, e o novo número de capacidade reportado (medido, nunca
estimado — diretriz do usuário 2026-08-08).

## Evidência-base (2026-08-16)

- Capacity report §11.11: 2→600/s · 4→~1.246/s (linear) · 8→~1.554/s (não
  linear: Postgres 500 conexões) · 16→degrada (sale_ok 94-97%, p95 ~10-19s).
- ADR-013: decisão registrada — correção (a) pool menor recomendada para
  scale-out imediato; PgBouncer candidato se >8 réplicas necessário.
- Pool Hikari atual: 100/réplica (Spec 60).

## Dependências

- **Spec 62** (capacity) — metodologia e baseline.
- **Spec 60** (infra hardening) — pool atual.
- **Spec 130** — rodar após o upgrade do Boot (não misturar variáveis).

## Fora de escopo

- Multi-região/DR — Spec 135.
- Fila dedicada — Spec 134 (independe do pool).
