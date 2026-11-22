---
name: Hardening de infraestrutura — Tomcat, Hikari e memória JVM
status: audited
references:
  - "management/vision.md#V40"
  - "management/specs/9-production-foundation/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 60 — Hardening de infraestrutura (Tomcat, Hikari, JVM)

## O que entrega

Corrige os achados **ALTOS** da análise de resiliência (2026-08-07) na camada
de infraestrutura — a app roda com defaults que não resistem a pico:
`server.tomcat` sem tuning (max-connections 8192, sem timeouts explícitos),
pool Hikari fixo em 10 (vs 200 threads do Tomcat) e heap JVM sem teto
(`MaxRAMPercentage=75` sem `-Xmx`, risco de OOM em instância Railway ~512MB).
Entregáveis:

1. **Tuning explícito do Tomcat**: `max-connections`, `accept-count`,
   `connection-timeout`, `keep-alive-timeout`, `max-keep-alive-requests` —
   dimensionados à instância Railway (anti slow-connectors/slowloris e DoS de
   sockets).
2. **Pool Hikari dimensionado**: `maximum-pool-size` coerente com threads e
   com o Postgres (Railway `max_connections` ~100); métrica de exhaustion
   documentada para alerta.
3. **Teto de heap JVM**: `JAVA_OPTS`/`-Xmx` explícito via Dockerfile + env do
   Railway, coerente com a RAM da instância e com threads/pool — sem OOM e sem
   GC thrashing sob pico.

## Critérios de aceite

1. `server.tomcat.*` explícito no `application.yml` (valores documentados e
   comentados; não-defaults justificados).
2. Sob teste de carga (k6 1000/5000/10000 VUs) contra a instância Railway:
   **sem** `connection refused` prematuro, **sem** `OutOfMemoryError`/restart
   loop, latência p95 estável no teto esperado.
3. Slow-connectors (conexão aberta sem request) são descartados em
   `connection-timeout`; keep-alive ocioso encerra em `keep-alive-timeout`.
4. Pool Hikari: sem `HikariPool connection timeout` no log sob pico;
   `hikaricp_connections` no Prometheus com folga do `maximum-pool-size`.
5. Heap: `jvm.memory.used` no Prometheus abaixo do teto sob pico; sem
   `OutOfMemoryError`; `JAVA_OPTS` documentado no runbook Railway.
6. `mvn test` sem regressão; E2E sem regressão (config de runtime não muda
   contrato de API).

## Filosofia de calibração (baseline ≠ definitivo)

Os valores (Tomcat, pool, heap) são **baseline inicial**, NÃO metas finais. O
objetivo **não é maximizar requisições/throughput** — é obter **degradação
controlada sob sobrecarga**: quando a capacidade é excedida, a app corta o
excesso (429/connection timeout) mantendo a latência dos aceitos estável e
**sem** OOM, restart loop, corrupção de ledger, perda de outbox ou quebra de
idempotência. Requer **benchmark/calibração no Railway** (k6 com ramp
progressivo 50→100→500→1000→5000 VUs): identificar o ponto de inflexão da
latência p95, ajustar threads/pool/heap **juntos** (interdependentes) e re-testar
até operar com folga **antes** do ponto de degradação severa. Resultado e
valores finais registrados no roadmap.

## Critérios de integridade sob sobrecarga

7. **Sem corrupção de ledger:** após pico, `wallet_balance = Σ ledger` por
   lojista (reconciliação Spec 30 passa); zero `SALE_CREDIT`/débito duplicado.
8. **Sem perda de outbox:** todos os eventos de SAÍDA (Spec 35/53) entregues ou
   em estado coerente (PENDING/FAILED com contagem consistente) após o pico;
   nenhum evento "sumiu".
9. **Idempotência mantida:** replay de cobrança/webhook continua 204/sem
   duplicidade mesmo no pico (Specs 26/28/36).
10. **Zero OOM / zero restart loop:** `jvm.memory.used` abaixo do teto;
    nenhum `OutOfMemoryError`; nenhum restart durante/pós-pico.

## Decisões de design

- **Tomcat** (values iniciais a calibrar no teste de carga):
  `max-connections: 1000`, `accept-count: 200`, `connection-timeout: 10000ms`,
  `keep-alive-timeout: 30000ms`, `max-keep-alive-requests: 100`.
- **Hikari**: `maximum-pool-size: 20` (query ~5ms → ~4000 req/s de DB; Postgres
  Railway suporta). `minimum-idle` default (10) mantido; metric de timeout no
  runbook.
- **JVM**: Dockerfile `ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]` +
  Railway `JAVA_OPTS=-Xmx320m` (**sem cap de metaspace** — achado 2026-08-08: o
  `-XX:MaxMetaspaceSize=96m` original causa `OutOfMemoryError: Metaspace` no
  boot; instância observada ~1GB);
  se threads/pool forem reduzidos (ex.: threads 150, pool 15), ajustar juntos.
