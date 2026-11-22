# Spec 62 — Design: Capacity Engineering & High-Throughput Transaction Processing

## Contexto

- A Spec 61 entregou HA funcionando (stateless, 2 réplicas Railway, healthcheck,
  graceful shutdown, rate limit Redis, zero-downtime) — mas **nenhum número de
  capacidade** foi medido além dos benchmarks pontuais das Specs 58/59/60
  (rate limit 120/min, webhook flood 300, infra 50→5000 VUs).
- O caminho de uma venda hoje (Specs 23/26/28/35/36): `POST` cobrança →
  idempotência (lock) → transação DB (wallet + ledger) → outbox (evento) →
  COMMIT → dispatcher entrega webhook de saída. O PSP (Pagar.me) é chamado no
  caminho da cobrança.
- Estado atual relevante:
  - Pool Hikari 20/réplica, `-Xmx320m`, Tomcat calibrado (Spec 60).
  - Rate limit distribuído via Redis, fail-open (Spec 43/58/59/61).
  - Scheduler com ShedLock no Postgres; outbox com retry e alerta `OUTBOX_DEAD`
    (Specs 53/54).
  - **Não há**: PgBouncer, réplicas de leitura, capacity model, load test por
    estágio de throughput com métricas completas, teste de explosão, prova de
    scale-out linear (2→16), medição de integridade sob carga.

## Decisões

0. **Regra de ouro (vinculante):** **não alterar a arquitetura para atingir
   5.000 vendas/s sem antes produzir evidência de que o componente é o
   gargalo.** PgBouncer, réplicas de leitura e Redis Cluster são intervenções
   **candidatas** — implementadas somente com evidência de benchmark; sem
   evidência, o critério é atendido registrando a medição que dispensou a
   mudança. O teste de capacidade (F7) mede o teto real (rate limit elevado);
   o 429 pertence ao teste de explosão (F8).
   **Regra complementar (mesma força, diretriz do usuário 2026-08-08):** **não
   fazer downgrade de qualidade, segurança, integridade ou observabilidade
   para economizar recursos** — nenhuma solução é simplificada, desligada ou
   degradada apenas para reduzir custo, componentes ou esforço; qualidade,
   segurança, integridade financeira (zero duplicação/perda, degradação
   controlada) e observabilidade são requisitos, não otimização. **E não
   declarar a meta (5.000 vendas/s) atingida até existir evidência
   experimental** — benchmark reproduzível (F7/F10); sem evidência, o número
   reportado é o medido, nunca o estimado. O par de regras é o balanço desta
   spec: complexidade só com evidência; qualidade/segurança/integridade/
   observabilidade nunca sacrificados por economia.

1. **Capacity model primeiro (fase 0)** — instrumentar e medir o custo real de
   uma venda por camada, em vez de assumir. Instrumentação:
   - `pg_stat_statements` ativo no Postgres (extensão) — queries por tipo, count,
     tempo total, rows.
   - Métricas Micrometer existentes (`nyx_*`, Spec 53) + novas se necessário:
     contadores por etapa da venda (idempotency, ledger, outbox).
   - Tracing distribuído (Spec 57) com sampling 100% **durante** os benchmarks
     para contar spans por camada (HTTP/JDBC/Redis).
   - **Saída:** documento `docs/capacity-model.md` com "1 venda = X requests,
     Y queries, Z writes, W Redis ops, V outbox, U webhooks" e a conversão para
     operações/s na meta (5.000 vendas/s → N ops/s por camada).

2. **PostgreSQL como alvo principal (fase 1)** — antes de qualquer mudança de
   arquitetura, provar o que o banco aguenta e onde dói:
   - Levantamento de N+1 e índices ausentes via `pg_stat_statements` +
   `EXPLAIN (ANALYZE, BUFFERS)` nas queries quentes.
   - Medir locks e duração de transações sob carga (o caminho da venda já usa
     `SELECT ... FOR UPDATE` na idempotência e no wallet — Specs 26/28/36).
   - WAL (write-ahead log) como gargalo candidato: volume de WAL por venda,
     IOPs, checkpoint tuning.
   - Correções cirúrgicas com evidência antes/depois (índices, queries,
     configuração), SEM aumentar pool arbitrariamente.

3. **PgBouncer entre API e PostgreSQL (fase 2) — GATED por evidência:**
   - **Gatilho:** o F1 mede contenção de conexões no Postgres sob carga
     (filas, limite de conexões, piora de throughput por contenção). PgBouncer
     só entra **se e somente se** essa medição apontar conexões como gargalo.
   - Aumentar conexões diretas pode piorar throughput por contenção; PgBouncer
     (transaction pooling) desacopla o número de conexões da API do limite do
     Postgres.
   - Deploy: container/serviço PgBouncer no Railway (ou junto do Postgres),
     `NYX_DB_URL` apontando para o PgBouncer; `server_default_pool_size`
     dimensionado.
   - **Risco conhecido:** transaction pooling + prepared statements —
     confirmar `PREPARE` por sessão desabilitado (Spring/Hibernate no modo
     adequado) ou usar session pooling até validar.
   - Verificação: benchmark comparando direto vs PgBouncer (throughput, p95,
     conexões no Postgres). **Sem evidência de gargalo: não implementar** — o
     pooling atual (Hikari 20, Spec 60) é mantido e a medição que o dispensou
     é registrada.

4. **Separação leitura/escrita (fase 3) — GATED por evidência:**
   - **Gatilho:** o F1 mede o peso do workload de leitura no primary; réplicas
     de leitura só entram **se e somente se** leituras comprovadamente
     competem com as escritas financeiras (storefront, dashboard, relatórios,
     admin).
   - **Regra de ouro:** ledger, wallet, idempotência, outbox e qualquer
     operação financeira continuam no caminho consistente de escrita (primary).
   - Implementação: `@Transactional(readOnly=true)` nas queries de leitura +
     rota de datasource (Spring `@RoutingDataSource` ou Hibernate
     `hibernate.connection.handling_mode`), com configuração por env
     (`NYX_DB_READ_URL`).
   - Atraso de réplica NUNCA afeta saldo exibido em contexto transacional;
     leituras eventualmente consistentes só em telas explicitamente não
     críticas (e documentado).
   - **Sem evidência de competição leitura×escrita: não implementar** — leituras
     seguem no primary e a medição que dispensou a mudança é registrada.

5. **Ledger/wallet como "zona sagrada" (fase 4)** — prova de integridade sob
   carga:
   - Durante e depois de cada estágio de benchmark: rodar a reconciliação da
     Spec 30 (`wallet_balance == SUM(ledger_entries)`) e reportar 0 divergência.
   - Idempotência em throughput: 10.000 requests concorrentes com a MESMA
     `idempotencyKey` → 1 transação, 0 duplicações (reusa lock + constraint da
     Spec 28; o teste de carga valida sob contenção real).
   - O resultado entra no relatório final como métrica obrigatória.

6. **Redis como infraestrutura de escala (fase 5)** — caracterizar e endurecer:
   - Medir latência, throughput, conexões, pool, memória, eviction sob carga.
   - Disponibilidade: failover; comportamento com Redis down (rate limit
     fail-open, Spec 43) e impacto medido.
   - Atomicidade do rate limit: re-verificar sob concorrência alta (já
     unitário na Spec 43; agora sob carga real).
   - Redis Cluster (sharding por hash slots): **só se o benchmark demonstrar**
     que o Redis single vira gargalo; documentar decisão no ADR.

7. **Outbox/caminho assíncrono (fase 6)** — garantir que a venda não espera o
   secundário:
   - Auditar o caminho crítico: REQUEST → validate → idempotency → DB
     transaction → ledger → outbox → COMMIT → RESPONSE. Remover qualquer
     chamada síncrona ao PSP/webhook/email/bot/delivery do caminho crítico se
     encontrada (a Spec 35/36 já separa; re-verificar).
   - Workers secundários (webhook, bot, notification, delivery, reconciliation)
     consomem o outbox em paralelo; capacidade de transação fica desacoplada da
     capacidade de processamento secundário.
   - Medir backlog do outbox por estágio (não pode crescer sem limite; deve
     escoar).

8. **Load test por estágio (fase 7)** — nova metodologia (substitui "N VUs"):
   - Estágios por **throughput alvo**: 100 → 250 → 500 → 1.000 → 2.000 → 3.000 →
     4.000 → 5.000 → 6.000 → 8.000 → 10.000 vendas/s (k6 com execução por
     estágio, ramp controlado).
   - **VUs × vendas/s:** k6 dirige VUs; a unidade de medida é **vendas/s
     alcançadas** — cada estágio documenta a calibração (VUs × taxa por VU) e
     o relatório registra AMBOS (VUs configurados e vendas/s medidas).
   - **Rate limit elevado durante a capacidade:** neste teste o rate limit é
     elevado acima do teto sob teste (ou desabilitado com justificativa
     documentada) para que o teto medido seja o gargalo real — o rate limit
     (120/min/IP, Specs 58/59) NÃO pode mascarar a capacidade. O 429 como
     degradação controlada é validado no teste de explosão (fase 8), não aqui.
   - Métricas obrigatórias por estágio (coletadas e arquivadas):
     throughput, vendas/s, p50/p95/p99, 4xx, 5xx, DB CPU, DB connections,
     Hikari active, Hikari pending, Redis latency, Redis ops/s, JVM heap, GC,
     API CPU, WAL, lock contention, deadlocks, ledger reconciliation,
     duplicações, outbox backlog.
   - Saída: tabela por estágio em `docs/capacity-report.md` + pontos de
     inflexão identificados.

9. **Teste de explosão (fase 8)** — após os sustentados: 5k → 7.5k → 10k → 15k →
   20k até quebrar:
   - Critério de "quebra bonita": 5xx ≈ 0, integridade 100%, ledger OK,
     0 duplicações, 0 outbox perdido; degradação via 429/backpressure (rate
     limit, Specs 58/59) ANTES de Postgres/pool/JVM morrer.
   - Ponto de ruptura registrado (limite conhecido do sistema).

10. **Scale-out linear (fase 9)** — 2 → 4 → 8 → 16 instâncias com o mesmo
    benchmark; esperado ~linear (2→1.500/s, 4→3.000/s, 8→6.000/s). Se a curva
    saturar, o gargalo central é caçado (pool do Postgres, Redis, WAL, lock
    contention) e corrigido antes de fechar.

11. **Relatório final (fase 10)** — `docs/capacity-report.md` consolida:
    "FoxCash Capacity v1" com número real (vendas/s, p50/p95/p99 por endpoint,
    % 5xx, 0 duplicações, 0 perda de ledger, 0 perda de outbox, réplicas),
    top-3 gargalos corrigidos, limites conhecidos (inflexão + ruptura) e
    recomendações pós-capacidade-v1 (o que só se faz se/quando o volume real
    exigir). **Reproduzível:** metodologia, ambiente (versões/instâncias),
    scripts k6, configuração do rate limit usada e janela temporal documentados
    — um segundo agente deve conseguir re-executar e comparar.

## Arquivos

- `docs/capacity-model.md` (novo) — custo de 1 venda por camada.
- `docs/capacity-report.md` (novo) — resultado final "Capacity v1".
- `pgbouncer/` (novo, se deploy) — config do PgBouncer.
- `load-tests/k6/` (existente, Specs 58-60) — scripts por estágio + explosão.
- `src/main/resources/application*.yml` — datasource read/write, PgBouncer URL.
- `src/main/java/.../config/` — routing datasource (se implementado).
- `management/adr.md` — ADR novo para PgBouncer + réplicas de leitura +
  Redis Cluster (se aplicável).

## Riscos e mitigações

- **PgBouncer + prepared statements** — risco de erro "prepared statement
  already exists" em transaction pooling; mitigado por validação antecipada e
  fallback para session pooling.
- **Réplica atrasada lendo saldo** — proibido por regra de design (fase 3);
  leituras financeiras sempre no primary.
- **Benchmark enganoso** — a metodologia exige estágios com métricas completas
  e arquivamento; nenhum "passou" sem a tabela de métricas.
- **Custo Railway** — réplicas/instâncias adicionais custam; benchmarks em
  janelas agendadas, instâncias derrubadas depois do teste.
- **Ambiente local (Windows)** — Docker daemon indisponível (WSL sem distros,
  visto na Spec 61); benchmarks pesados no Railway, validações de unidade/
  integração locais com contagens menores.
