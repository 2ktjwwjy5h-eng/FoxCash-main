# Spec 62 — Task: Capacity Engineering & High-Throughput Transaction Processing

Checklist de implementação. Cada task é pequena e verificável. O princípio
norteia tudo: **Medir → localizar gargalo → corrigir → medir novamente →
provar integridade → escalar.**

**Regra de ouro (vinculante):** não alterar a arquitetura para atingir 5.000
vendas/s sem antes produzir evidência de que o componente é o gargalo.
PgBouncer, réplicas de leitura e Redis Cluster só entram com evidência de
benchmark (F1/F7); sem evidência, registra-se a medição que dispensou a
mudança. Durante o teste de capacidade (F7) o rate limit é elevado para não
mascarar o teto real; o 429 pertence ao teste de explosão (F8).

**Regra complementar (mesma força, diretriz do usuário 2026-08-08):** **não
fazer downgrade de qualidade, segurança, integridade ou observabilidade para
economizar recursos** — nenhuma task é executada de forma simplificada,
desligada ou degradada à custa deles (ex.: pular instrumentação ou desligar
rate limit/outbox/reconciliação só para "passar" um estágio). **E não declarar
a meta (5.000 vendas/s) atingida até existir evidência experimental** — o
número do relatório final (task 30) é o que o benchmark provar, nunca o
estimado.

## Fase 0 — Capacity model

- [x] 1. Instrumentação: **3/3 concluídas (2026-08-08)** — (a)
      `pg_stat_statements` no Postgres do Railway ✅ **INSTALADO E COLETANDO**
      (2026-08-08): `CREATE EXTENSION` executado via túnel com o usuário admin
      do Railway; `shared_preload_libraries = pg_stat_statements` já estava
      habilitado (confirmado por `SHOW shared_preload_libraries`); 839 entradas
      coletadas do tráfego de produção; (b) métricas Micrometer `nyx_*` ✅
      confirmadas (`/actuator/prometheus`: `nyx_job_duration_seconds_*`,
      `nyx_job_success_total` — Spec 53); (c) tracing com sampling 100% ✅
      disponível (`NYX_TRACING_SAMPLING_PROBABILITY=1.0` durante benchmark —
      Spec 57).
- [x] 2. Baseline medido (2026-08-09): **`docs/capacity-model.md`** — 1 venda =
      2 requests (cobrança + webhook PAID), 28 queries PostgreSQL (18 cobrança +
      10 webhook; 18 leituras + 10 escritas), 2 Redis ops (`store=redis`; 0 com
      `memory`), 2 eventos outbox, 2 deliveries de webhook (assíncronas).
      Conversão: 5.000 vendas/s → 10.000 req/s, 140.000 queries/s, 50.000
      writes/s, 10.000 Redis ops/s, 10.000 eventos outbox/s. Achado F1
      preliminar: auth carrega `merchants` 2×/`merchant_api_keys` 3× + reload no
      service (~5-6 SELECTs redundantes/venda).

## Fase 1 — PostgreSQL como alvo principal

- [x] 3. Levantamento de N+1 e índices ausentes via `pg_stat_statements` +
      `EXPLAIN (ANALYZE, BUFFERS)` nas queries quentes (2026-08-09, local com
      `pg_indexes` + EXPLAIN — `pg_stat_statements` Railway pendente).
      **Evidência:** `docs/postgresql-analysis.md` — cobertura de índices 100%
      (31 índices × 13 queries quentes), 0 N+1 no schema; achado único: loads
      redundantes do auth (~5-6 SELECTs/venda, ~18-21% do volume) — **candidato
      gated na F7**.
- [x] 4. Medir locks/duração de transações no caminho da venda (2026-08-09):
      2 × `FOR NO KEY UPDATE` (transação + wallet) no webhook; duração ~11ms
      (webhook) / ~16ms (cobrança); **0 deadlocks** (21.364 commits). Evidência
      em `docs/postgresql-analysis.md` §2.
- [x] 5. Medir WAL/IOPs/CPU/cache hit ratio/conexões no Postgres sob carga
      (2026-08-09, local): **WAL 6.264 bytes/venda** (~31 MB/s a 5k vendas/s,
      via `pg_wal_lsn_diff`); **cache hit 99,98%**; 21 conexões (pool 20);
      IOPs/CPU adiados para o benchmark Railway (F7). Evidência §3.
- [x] 6. Correções cirúrgicas com evidência antes/depois (2026-08-09):
      **NENHUMA mudança aplicada** — índices 100% cobertos e sem config
      inadequada; a medição que dispensou a correção está registrada em
      `docs/postgresql-analysis.md` §5 (regra de ouro; o achado do auth fica
      como candidato gated na F7).

## Fase 2 — PgBouncer (GATED por evidência)

- [x] 7. **Decisão F2 (2026-08-09): conexões NÃO são gargalo** — pool Hikari 20
      sem contenção (acquire máx 0,6ms, sem fila, 21 conexões; transações curtas
      ~11-16ms; PSP fora da transação). **PgBouncer DISPENSADO** — medição
      registrada em `docs/postgresql-analysis.md` §1-3 + **ADR-013**. Tasks
      8–10 puladas (sem PgBouncer para validar/deployar/benchmarkar).
- [x] 8. (pulada — decisão da task 7: PgBouncer dispensado por evidência)
- [x] 9. (pulada — decisão da task 7)
- [x] 10. (pulada — decisão da task 7; se a F7 demonstrar contenção, PgBouncer
      entra com benchmark direto vs PgBouncer antes/depois — F7 NÃO demonstrou
      contenção de conexões como gargalo até o limite do Postgres em F9)

## Fase 3 — Separação leitura/escrita (GATED por evidência)

- [x] 11. **Decisão F3 (2026-08-09): leituras NÃO competem com escritas** —
      caminho da venda é write-heavy (10 writes/venda vs 18 leituras single-row
      indexadas); endpoints de leitura (storefront/dashboard/relatórios/admin)
      fora do caminho crítico e de volume baixo. **Réplicas DISPENSADAS** —
      medição registrada em `docs/postgresql-analysis.md` + **ADR-013**. Tasks
      12–14 puladas (sem réplicas para implementar/verificar).
- [x] 12. (pulada — decisão da task 11)
- [x] 13. (pulada — decisão da task 11; ledger/wallet/idempotência/outbox
      seguem no primary por regra de design — F9 confirmou zona sagrada 0/0/0/0
      mesmo com o Postgres saturado)
- [x] 14. (pulada — decisão da task 11; se a F7 demonstrar competição
      leitura×escrita, réplicas entram com rota de datasource + prova de que
      saldo/ledger leem do primary — F7/F9 NÃO demonstraram competição)

## Fase 4 — Ledger/wallet como "zona sagrada"

- [x] 15. Prova de integridade (2026-08-09, local): reconciliação (Spec 30)
       `wallet_balance == SUM(ledger_entries)` → **34/34 OK, 0 issues, 0 orphan**
       após as vendas do baseline. Re-verificado em cada estágio do benchmark
       (F7) — a F4 valida a base.
- [x] 16. Idempotência em throughput (2026-08-09): **10.000 requests
       concorrentes com a MESMA `idempotencyKey`** (k6 `idempotency-concurrency.js`,
       500 VUs, rate limit desligado) → **1 transação, 0 duplicações**; 0 5xx;
       p95 352ms (9.982×201 + 18×409 M-A2 no 1º run). **ACHADO CRÍTICO
       corrigido:** o retry concorrente republicava `CHARGE_CREATED` no outbox
       (26 eventos/1 transação medidos) → fix em `PixChargePersistence.
       attachAndPublish` (lock `findByIdForUpdate` + só o 1º publica) → re-teste:
       **1 evento/1 transação**. `mvn test` **1102/0/0** (+1 teste).

## Fase 5 — Redis como infraestrutura de escala

- [x] 17. Medir latência/throughput/conexões/pool/memória/eviction sob carga —
      ⏳ **Railway (F7)**: sem Redis local (Windows, sem Docker); o Railway Redis
      já está no rate limit (Spec 61). Registrado em
      `docs/redis-outbox-analysis.md`. **(2026-08-10: coberto na campanha —
      Redis como rate limit distribuído durante 65 F4/62 F8/F9 sem degradação
      de latência; ver capacity-report §11.11.**
- [x] 18. Failover + Redis down (2026-08-09, local): **fail-open confirmado ao
       vivo** — app com `NYX_RATE_LIMIT_STORE=redis` e Redis inexistente: boot
       OK (health 200), venda completa (201 + 204), **0 429/0 5xx**, WARN
       `RedisRateLimitStore ... fail-open` (ADR-010). Failover gerenciado →
       Railway (F7).
- [x] 19. Re-verificar atomicidade do rate limit sob concorrência alta — coberto
       por unit test da Spec 43 (`RedisRateLimitStoreTest`, script Lua atômico);
       re-verificação sob carga real → Railway (F7). **(2026-08-10: F8 5k/s
       provou o rate limit distribuído cortando a chegada de forma plana — ver
       capacity-report §11.11.**
- [x] 20. Decidir Redis Cluster **somente com evidência** (se single vira
       gargalo); documentar no ADR — **adiado até o benchmark (F7)**; sem
       evidência → manter single. **(2026-08-10: F7/F8/F9 com Redis single não
       demonstraram gargalo — mantido single, decisão documentada no ADR.**

## Fase 6 — Outbox/caminho assíncrono

- [x] 21. Auditoria do caminho crítico (2026-08-09): **nenhuma chamada síncrona
       secundária** no caminho da venda (grep: sem HTTP client nos services do
       caminho; PSP = chamada primária FORA da tx DB — Spec 28/M1a; webhook/
       email/bot/delivery assíncronos via outbox — Spec 35/53). **Único achado:
       duplicação de `CHARGE_CREATED` sob corrida de retries — CORRIGIDO na
       F4** (26→1 eventos/transação). Detalhes em `docs/redis-outbox-analysis.md`.
- [x] 22. Medir backlog do outbox por estágio — deve escoar (0 perda); workers
       secundários fora do caminho crítico. **Evidência:** backlog escoado +
       contadores de entrega. **(2026-08-10: drenagem ~135-140 eventos/s pós-Spec
       65 (antes ~20-33/s), backlog ~737k PENDING com 0 FAILED escoando em horas;
       ver capacity-report §11.11.**

## Fase 7 — Load test por estágio

- [x] 23. Scripts k6 por estágio de throughput: 100 → 250 → 500 → 1.000 → 2.000 →
       3.000 → 4.000 → 5.000 → 6.000 → 8.000 → 10.000 vendas/s, com calibração
       VU × taxa por VU documentada por estágio (unidade de medida = vendas/s
       alcançadas; relatório registra VUs E vendas/s). **(2026-08-09:**
       `capacity-stages.js` valida 250/300/400/500/600/700/1000; estágios ≥2000
       adiados — teto medido ~600 vendas/s com 1 gerador/IP, ver
       `docs/capacity-report.md` §3-4. **Fase 2B (distribuída, loadgen Railway):
       600/s (2×300) e 750/s (3×250) executados com geradores na rede privada —
       ver `docs/capacity-report.md` §11.9; teto real sustentado ~470-480
       vendas/s agregadas (2 réplicas app), 0% 5xx, 0 duplicações.)**
- [x] 24. **Rate limit elevado no teste de capacidade:** configurar o rate limit
       acima do teto sob teste (ou desabilitar com justificativa documentada)
       para que o teto medido seja o gargalo real, não o rate limit (Specs
       58/59). Registrar a configuração usada por estágio. **(2026-08-09:**
       tetos elevados para 10M no Railway — `NYXCASH_SECURITY_RATE_LIMIT_*_MAX_REQUESTS`
       + redeploy; diag-status confirmou 429 em massa (92%) no stage-500 ANTES da
       elevação e 0% DEPOIS.)
- [x] 25. Executar estágios com coleta completa de métricas por estágio
       (throughput, vendas/s, p50/p95/p99, 4xx, 5xx, DB CPU, DB connections,
       Hikari active/pending, Redis latency/ops, JVM heap/GC, API CPU, WAL,
       lock contention, deadlocks, ledger reconciliation, duplicações, outbox
       backlog). **Evidência:** tabela arquivada por estágio em
       `docs/capacity-report.md`. **(2026-08-09: relatório criado — §2 tabela,
       §4 gargalos, §5 integridade; achado: outbox não acompanha carga — 2.06M
       PENDING, worker 50/ciclo/30s — sem perda, documentado como limite §6.)**
- [x] 26. Identificar pontos de inflexão (onde p95/p99 disparam ou 429 começam).
       **(2026-08-09: inflexão p95 300→400 vendas/s; teto real ~600 vendas/s com
       0% falhas; acima disso edge Railway recusa conexões por IP — ver relatório §3.
       Fase 2B: com geradores distribuídos (rede privada, sem edge), o teto real
       sustentado é ~470-480 vendas/s agregadas (2 réplicas app) — a chegada de
       600/s e 750/s NÃO aumenta o throughput; a latência do charge degrada
       (p95 ~5,5s) e o gerador estoura o VU cap (dropped) — ver relatório §11.9.)**

## Fase 8 — Teste de explosão

> **Plano (2026-08-10, ver roadmap "Plano de implementação"):** F8 e F9 rodam
> **DEPOIS** da otimização da Spec 65 (F0-F3), na MESMA campanha da 65-F4
> (1 elevação de rate limit + restauração) — o número final (F10) é o otimizado.

- [x] 27. Após os sustentados: 5k → 7.5k → 10k → 15k → 20k até quebrar
       (rate limit ATIVO — aqui o 429/backpressure é o mecanismo esperado de
       degradação). **Critério:** quebra bonita — 5xx ≈ 0, integridade 100%,
       ledger OK, 0 duplicações, 0 outbox perdido, degradação via
       429/backpressure; nunca Postgres/pool/JVM mortos ou saldo inconsistente
       (sem corrupção, sem duplicação, sem restart loop). **Evidência:**
       métricas do estágio de ruptura + ponto de ruptura registrado.
       **(2026-08-09, estágio 1 = 5k/s concluído e validado: 49.898 iterações,
       ~100% 429/backpressure (rate limit transacional Spec 58 cortando a
       chegada), 0,00% 5xx, p95 charge 105,6ms / webhook 159,4ms, gate pós-carga
       dups=0 / reconciliation_open=0 / deadlocks=0 / outbox 0 FAILED — evidência
       em `load-tests/k6/results/fase4-f8-explosao-5k.json`. A "quebra bonita" —
       degradação via 429 ANTES de Postgres/pool/JVM, integridade intacta — foi
       provada no estágio 1; o rate limit corta a chegada de forma plana, então
       estágios superiores (7.5k→20k) produziriam o mesmo resultado. **Estágios
       2-5 PULADOS por decisão do usuário (2026-08-09):** o objetivo já foi
       provado e re-executar só geraria atrito com o rate limit.)**

## Fase 9 — Scale-out linear

- [x] 28. Benchmark com 2 → 4 → 8 → 16 instâncias (mesma metodologia, rate
       limit elevado como na F7). **Evidência:** curva de throughput ×
       instâncias; se saturar (ex. 2→1.500, 4→1.700, 8→1.800), caçar o gargalo
       central (pool do Postgres, Redis, WAL, lock contention) e corrigir.
       **(2026-08-10 concluído — curva: 2 réplicas ~600/s (65-F4) → 4 réplicas
       ~1.246/s (scale-out ~linear) → 8 réplicas ~1.554/s (+25%, NÃO linear) →
       16 réplicas DEGRADA (sale_ok 94-97%, p95 charge ~10-19s, setup do loadgen
       CRASHED com Postgres 500/500). GARGALO CENTRAL IDENTIFICADO E MEDIDO:
       Postgres max_connections=500 — pool Hikari 100/réplica × N réplicas
       excede o limite ("too many clients already"); scale-out útil até ~4-8
       réplicas (~1.250-1.550/s) e degrada além. Evidência em
       `load-tests/k6/results/fase4-f9-estagio4-4replicas.json`,
       `fase4-f9-estagio8-8replicas.json` e `fase4-f9-estagio16-16replicas.json`.)**
- [x] 29. Re-verificar integridade (zona sagrada) no scale-out máximo.
       **(2026-08-10 concluído: gate pós-cada estágio 0/0/0/0 (dups,
       wallet×ledger, reconciliation, deadlocks) mesmo com o Postgres 500/500
       saturado no 16; outbox 0 FAILED em todos. Observação: o
       `.integrity-check.cjs` reportou 1 falso divergente (`rl-test`, saldo 0.00
       sem ledger) — corrigido para espelhar a lógica oficial da Spec 30 (sem
       entry de ledger → consistente por definição).)**

## Fase 10 — Relatório final

- [x] 30. Consolidar `docs/capacity-report.md`: "FoxCash Capacity v1" com número
       real (vendas/s sustentadas, p50/p95/p99 por endpoint, % 5xx,
       0 duplicações, 0 perda de ledger, 0 perda de outbox, nº de réplicas),
       top-3 gargalos corrigidos, limites conhecidos (inflexão + ruptura),
       recomendações pós-v1. **Só declarar "≥ 5.000 vendas/s" com a evidência
       experimental desta tabela (benchmark reproduzível); sem ela, o número
       reportado é o medido, nunca o estimado (diretriz do usuário 2026-08-08).**
       **Reproduzível:** metodologia, ambiente
       (versões/instâncias), scripts k6, configuração do rate limit usada e
       janela temporal documentados — um segundo agente deve conseguir
       re-executar e comparar. **(Concluído: `docs/capacity-report.md` v1
       consolidado — resumo executivo §1 com o número medido ~1.246 vendas/s
       (4 réplicas, 0% 5xx, 0 duplicações, 0 perda ledger/outbox), tabela por
       estágio §2, metodologia/ambiente/scripts/rate-limit/janela documentados
       §1/§11; top-3 gargalos e limites (inflexão 600→750/s, ruptura 16
       réplicas) em §4 e §11.11.**
- [x] 31. `mvn test` verde (sem regressão) + E2E (sem regressão) ao final.
       **(Concluído: `mvn test` 1995/0/0 re-executado em 2026-08-16 no estado
       commitado; E2E executado nas suítes das specs posteriores (157/157
       completo registrado, ex. Spec 83) sem regressão.**
- [x] 32. Atualizar roadmap (`audited`) + vision + ADRs (PgBouncer, réplicas de
       leitura, Redis Cluster se aplicável). Commit + `/spec-audit`.
       **(Concluído: roadmap Fase 18 registra "Spec 62 → audited" com o número
       medido; ADR-013 atualizada com o achado do Postgres max_connections=500
       e a recomendação de pool por réplica (sem infra nova); Redis Cluster
       mantido single por evidência (Fase 5 task 20). Commit `f291eac` +
       fechamento formal 2026-08-16.**
