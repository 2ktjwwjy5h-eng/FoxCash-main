---
name: Capacity Engineering & High-Throughput Transaction Processing
status: audited
references:
  - "management/vision.md#V42"
  - "management/specs/61-horizontal-scalability-ha/requirements.md"
  - "management/specs/60-infra-hardening/requirements.md"
  - "management/specs/58-rate-limit-autenticado/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/43-rate-limit-redis/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/28-charge-idempotency/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/adr.md#ADR-010"
  - "management/adr.md#ADR-011"
---

# Spec 62 — Capacity Engineering & High-Throughput Transaction Processing

## O que entrega

Transformar o FoxCash de "HA funcionando" (Spec 61) em uma arquitetura com
**capacidade mensurável** e caminho de escala para **≥ 5.000 vendas/s
sustentadas** e **1M+ usuários cadastrados**, sem sacrificar integridade
financeira (ledger, idempotência, outbox, webhooks, disponibilidade).

A Spec 61 provou HA (stateless, réplicas 2, healthcheck, zero-downtime) mas
**não entrega um número real de capacidade**. A Spec 62 mapeia o custo real de
uma venda por camada, localiza gargalos com evidência, aplica correções
cirúrgicas e termina com um **número real de capacidade medido**, no formato:

> **FoxCash Capacity v1: N vendas/s sustentadas, p95 X ms, 0,00% 5xx, 0
> duplicações, 0 perda de ledger, 0 perda de outbox, com K réplicas.**

Princípio: **Medir → localizar gargalo → corrigir → medir novamente → provar
integridade → escalar.**

## Regra de ouro (vinculante)

> **Não alterar a arquitetura para atingir 5.000 vendas/s sem antes produzir
> evidência de que o componente é o gargalo.**

**Regra complementar (mesma força, diretriz do usuário 2026-08-08):** **não
fazer downgrade de qualidade, segurança, integridade ou observabilidade para
economizar recursos** — nenhuma solução é simplificada, desligada ou degradada
apenas para reduzir custo, número de componentes ou esforço de implementação.
Qualidade, segurança, integridade financeira (zero duplicação/perda, degradação
controlada) e observabilidade são **requisitos**, não otimização.

As duas regras formam o balanço desta spec — e de todo o projeto:

- **Sem otimização prematura:** complexidade (PgBouncer, réplicas, Redis
  Cluster, particionamento, etc.) entra **somente com evidência** de gargalo;
  sem evidência, registra-se a medição que dispensou a mudança.
- **Sem downgrade de qualidade/segurança/integridade/observabilidade:** nenhuma
  decisão é tomada apenas para reduzir custo/componentes/esforço se ela
  enfraquecer qualidade, segurança, integridade financeira, não-perda de
  ledger/outbox, idempotência, observabilidade ou degradação controlada. Ex.:
  não pular instrumentação (`pg_stat_statements`/EXPLAIN/tracing) para
  "economizar tempo"; não desligar rate limit/outbox/reconciliação num estágio
  só para "passar" mais rápido; não escolher a solução com menos componentes
  sem evidência de que mantém a robustez exigida.

Consequências diretas:

- PgBouncer, réplicas de leitura e Redis Cluster são **intervenções
  candidatas**, implementadas **somente se o benchmark (F1/F7) demonstrar**
  que o componente correspondente é gargalo — nunca infraestrutura obrigatória
  prematura.
- Toda mudança de arquitetura deve ser precedida por medição (antes/depois)
  que a justifique.
- O teste de capacidade (F7) mede o **teto real** do sistema; o rate limit não
  pode mascarar o gargalo verdadeiro durante esse teste (ver critério 8).

## Meta de engenharia

- ≥ 5.000 vendas/s sustentadas (objetivo de capacidade, não promessa — vale o
  número que o benchmark provar). **Regra de evidência (diretriz do usuário
  2026-08-08): não declarar que a meta de 5.000 vendas/s foi atingida até
  existir evidência experimental** — benchmark reproduzível (F7/F10) com
  métricas completas arquivadas em `docs/capacity-report.md`. Sem evidência, o
  número reportado é o que foi **medido**, nunca o estimado/planejado.
- ≥ 1.000.000 usuários cadastrados (capacidade de dados + autenticação).
- Múltiplas instâncias da API (scale-out linear).
- Zero duplicação de transação (mesma `idempotencyKey` → 1 transação, mesmo sob
  concorrência e throughput alto).
- Zero perda de ledger (`wallet_balance == SUM(ledger_entries)` durante e
  depois de carga pesada).
- Idempotência preservada sob concorrência.
- Webhook/outbox sem perda (backlog escoa; nada é descartado).
- p95/p99 definidos por endpoint (publicados no resultado final).
- Degradação controlada sob overload (429/backpressure — nunca crash de
  Postgres/pool/JVM ou saldo inconsistente).
- Scale-out sem alterar a semântica financeira.

## Fora de escopo (explicitamente NÃO faz parte)

- Trocar PostgreSQL por outro banco **sem evidência** (PostgreSQL tem caminho de
  escala comprovado: pooling, réplicas, isolamento de workloads, otimização de
  queries, particionamento — só se abandona com dados).
- Microservices por moda.
- Aumentar threads arbitrariamente.
- Aumentar connection pool arbitrariamente.
- Remover rate limiting.
- Sacrificar consistência financeira por throughput.

## Critérios de aceite

1. **Capacity model documentado** — uma venda = quantas requests, queries
   PostgreSQL, writes, operações Redis, eventos outbox e webhooks. O modelo
   converte "5.000 vendas/s" em operações/s reais por camada (ex.: 5.000 × 8 =
   40.000 operações/s) e fundamenta o dimensionamento.
2. **PostgreSQL mapeado como alvo principal** — relatório com queries/writes por
   venda, N+1 identificados, índices ausentes/subutilizados, locks e duração de
   transações, deadlocks, connection pool, `pg_stat_statements`, slow queries,
   WAL, IOPs, CPU, cache hit ratio, número de conexões. Correções aplicadas com
   evidência de antes/depois.
3. **Connection pooling (PgBouncer) — SOMENTE com evidência** — o F1 mede
   contenção de conexões no Postgres sob carga; **se e somente se** o benchmark
   demonstrar que conexões são gargalo (piora de throughput por contenção,
   fila/limite de conexões), implementa-se PgBouncer entre API e PostgreSQL com
   benchmark antes/depois (throughput, p95, conexões). Sem essa evidência, o
   pooling atual (Hikari 20, Spec 60) é mantido e o critério é atendido
   registrando-se a medição que o dispensou.
4. **Separação leitura/escrita — SOMENTE com evidência** — o F1 mede o peso do
   workload de leitura no primary; **se e somente se** o benchmark demonstrar
   que leituras competem com escritas financeiras, implementam-se réplicas de
   leitura — com a **regra de ouro** de que **ledger, wallet, idempotência,
   outbox e qualquer operação financeira permanecem no caminho consistente de
   escrita (primary)** (saldo de vendedor NUNCA depende de réplica atrasada).
   Sem essa evidência, leituras seguem no primary e o critério é atendido com a
   medição registrada.
5. **Ledger/wallet como "zona sagrada"** — provado sob carga pesada:
   `wallet_balance == SUM(ledger_entries)` durante e depois; 10.000 requests com
   a mesma `idempotencyKey` → **1 transação, 0 duplicações** (concorrência +
   throughput).
6. **Redis como infraestrutura de escala** — latência, throughput, conexões,
   pool, memória, eviction, disponibilidade, failover, comportamento com Redis
   down (fail-open mantido) e atomicidade do rate limit; Redis Cluster
   **apenas se o benchmark demonstrar necessidade** (sharding por hash slots,
   documentado).
7. **Outbox com caminho crítico curto** — o caminho da venda não espera
   processamento secundário: REQUEST → validate → idempotency → DB transaction →
   ledger → outbox → COMMIT → RESPONSE. Webhook/bot/notification/delivery/
   reconciliation consomem o outbox fora do caminho crítico. Capacidade de
   transação é separada da capacidade de processamento secundário.
8. **Load test por estágio de throughput** — estágios: 100 → 250 → 500 → 1.000 →
   2.000 → 3.000 → 4.000 → 5.000 → 6.000 → 8.000 → 10.000 vendas/s. Cada estágio
   mede: throughput, vendas/s, p50/p95/p99, 4xx, 5xx, DB CPU, DB connections,
   Hikari active, Hikari pending, Redis latency, Redis ops/s, JVM heap, GC, API
   CPU, WAL, lock contention, deadlocks, ledger reconciliation, duplicações,
   outbox backlog. (Não basta "10.000 VUs passaram".)
   - **VUs × vendas/s:** o gerador (k6) dirige VUs; a unidade de medida do teste
     é **vendas/s alcançadas** (calibração VU × taxa por VU documentada em cada
     estágio; o relatório registra AMBOS: VUs configurados e vendas/s medidas).
   - **Rate limit NÃO mascara o gargalo:** durante o teste de capacidade (F7) o
     rate limit é elevado acima do teto sob teste (ou desabilitado com
     justificativa documentada) para que o teto medido seja o gargalo real do
     sistema — NUNCA o rate limit (teto 120/min/IP, Specs 58/59). A
     degradação via 429 pertence ao teste de explosão (critério 9), não ao de
     capacidade.
9. **Teste de explosão** — após os sustentados: 5k → 7.5k → 10k → 15k → 20k até
   quebrar. A quebra precisa ser bonita: 5xx ≈ 0, integridade 100%, ledger OK,
   0 duplicações, 0 outbox perdido → depois **429/backpressure** — nunca
   Postgres morto → pool morto → JVM morta → instâncias reiniciando → saldo
   inconsistente.
10. **Scale-out linear** — 2 → 4 → 8 → 16 instâncias; throughput esperado
    ~linear (ex.: 2→1.500/s, 4→3.000/s, 8→6.000/s). Se a curva saturar
    (2→1.500, 4→1.700, 8→1.800) → gargalo central identificado e corrigido.
11. **Resultado final com número real e reproduzível** — "FoxCash Capacity v1"
    com: vendas/s sustentadas, p50/p95/p99 por endpoint, % 5xx, 0 duplicações,
    0 perda de ledger, 0 perda de outbox, nº de réplicas, top-3 gargalos
    encontrados e corrigidos, e limites conhecidos (ponto de inflexão + ponto
    de ruptura). O relatório é **reproduzível**: metodologia, ambiente
    (versões/instâncias), scripts k6, configuração do rate limit usada e
    janela temporal do teste documentados — um segundo agente deve conseguir
    re-executar e comparar.

## Dependências

- **Spec 61** (HA: réplicas, healthcheck, rate limit Redis, graceful shutdown) —
  pré-requisito; a Spec 62 usa o mesmo runbook de deploy e a verificação
  multi-instância.
- **Spec 60** (infra hardening: Tomcat/Hikari/JVM) — baseline de tuning já
  calibrado (pool 20, `-Xmx320m`).
- **Spec 58/59** (rate limit) — a degradação controlada via 429 depende do rate
  limit por usuário/IP funcionando; a Spec 62 prova o comportamento sob
  overload, não o recria.
- **Specs 27/28/30** (ledger, idempotência, reconciliação) — a prova de
  integridade ("zona sagrada") usa as ferramentas que essas specs entregaram.
- **Spec 53/54** (jobs, outbox alerta) — o outbox e o alerta de evento esgotado
  são a base do caminho assíncrono e da não-perda.
- **Spec 43/ADR-010** (rate limit Redis) — atomicidade e fail-open.
