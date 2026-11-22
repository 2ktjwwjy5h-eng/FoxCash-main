# Spec 61 — Task: escalabilidade horizontal e alta disponibilidade

- [x] 1. `application.yml`: `server.shutdown: graceful` +
      `spring.lifecycle.timeout-per-shutdown-phase: ${NYX_SHUTDOWN_TIMEOUT:30s}`
      (env override sem rebuild). **Evidência:** contexto carrega em 1101 testes;
      app boots com as propriedades (health 200).
- [x] 2. Smoke graceful shutdown (dev). **Evidência parcial:** config validada
      (tests + boot); o **dreno vivo por SIGTERM não foi reproduzível no
      Windows** (processo JVM destacado recebe `^C` via console, mas o Windows
      o encerra sem rodar os hooks do Spring — limitação de sinais do SO, não
      da config). A validação do dreno real fica na **task 9 (Railway/Linux)**,
      onde o SIGTERM do orquestrador roda os hooks do JVM (garantia do JDK).
- [x] 3. `railway.json`: `deploy.replicas: 2` + `healthcheckPath:
      /actuator/health/readiness` + `healthcheckTimeout: 300` +
      `healthcheckInterval: 30`. (Validar as chaves contra o schema oficial do
      Railway no deploy — task 9.)
- [x] 4. `docker-compose.yml`: service `redis` (redis:7-alpine) + serviços
      `api-a`/`api-b` (build da mesma imagem do Dockerfile, mesmo Postgres):
      `SPRING_PROFILES_ACTIVE=dev` (secrets dev-only — crypto/admin/webhook),
      `DB_URL=jdbc:postgresql://postgres:5432/nyxcash` (base aponta p/
      localhost), `NYX_RATE_LIMIT_STORE=redis` + `SPRING_DATA_REDIS_HOST=redis`;
      portas do host `8081:8080`/`8082:8080` (o app escuta em 8080 no yml base;
      Railway usa `$PORT` no profile railway). **`docker compose config` OK.**
- [x] 5. Verificação multi-instância — **executada com 2 instâncias JVM locais**
      (Docker daemon indisponível no ambiente: WSL sem distros; cenários 5a/5b/
      5d/5e independem de Redis e foram provados com 2 processos `java -jar`
      contra o mesmo Postgres; o 5c depende de Redis → evidência unitária da
      Spec 43 + task 9):
      - [x] 5a. **Sessão cross-instância**: master criado via SQL → login na A
            (8080) → token usado na B (8082) → **200**; controle sem token →
            **401** (sessões DB-backed).
      - [x] 5b. **Scheduler 1×**: com A+B no ar, `job_runs` mostra **1 execução
            por ciclo de 30s** (01:30:26→01:32:26) e **0 overlaps**; lock
            distribuído (A=1, B=4 execuções) — ShedLock no Postgres.
      - [ ] 5c. **Rate limit agregado (Redis)**: sem daemon Docker/Redis no
            ambiente — **deferido para a task 9 (Railway)**. Evidência
            indireta: `RedisRateLimitStoreTest` (6 testes, Spec 43) valida o
            script Lua atômico (INCR+EXPIRE, TTL, fail-open).
      - [x] 5d. **Idempotência cross-instância**: cobrança com chave K na A →
            **201**; replay da MESMA chave na B → **mesma transação
            (19577c28...)**, DB com **1 transação PENDING** (sem duplicidade).
      - [x] 5e. **Failover**: A derrubada (crash) → B responde **200** (health)
            e request autenticado na B → **200** (sessão sobrevive à morte da A).
- [x] 6. Auditoria de estado em memória — **concluída com evidência** (busca no
      código: sem `HttpSession`/`@SessionAttributes`/`@Cacheable`/CacheManager/
      `static` mutável; inventário completo no design.md — único estado
      relevante: `InMemoryRateLimitStore` → trocado por `redis`).
- [x] 7. `deploy/railway.md`: seção HA — replicas, healthcheck, env vars
      multi-instância (`NYX_RATE_LIMIT_STORE=redis`, `SPRING_DATA_REDIS_*`,
      `NYX_SHUTDOWN_TIMEOUT`), `NYX_DB_POOL_SIZE` por réplica (Spec 60,
      respeitando `max_connections` do Postgres Railway), rollout/rollback,
      verificação pós-deploy (health + métricas Prometheus).
- [x] 8. `mvn test` sem regressão + E2E sem regressão — **`mvn test` 1101/0/0**
      e **E2E Playwright 104/104 (5.8m)** — config de runtime não muda contrato.
- [x] 9. (Opcional/Railway) deploy com `numReplicas=2` + verificação ao vivo
      (2026-08-08): health **200** (liveness/readiness por réplica),
      healthcheck `[2/2] Healthcheck succeeded!` (fix 744c1f0 — o healthcheck
      do Railway sonda via HTTP puro e o `requiresChannel` respondia 302;
      eximido `/actuator/health/**` com `ChannelDecisionManagerImpl.ANY_CHANNEL`),
      **rollout sem downtime** (3 redeploys sob tráfego: 516 req contínuas +
      burst 30 concorrentes ~1800 req — zero falhas), **dreno do graceful
      shutdown por SIGTERM** confirmado na prática (in-flight completam; logs
      "Commencing graceful shutdown" não capturados pelo Railway p/ deploys
      drenados), **rate limit agregado via Redis (5c)**: flood webhook 300 →
      120×401 + 180×429 (contador compartilhado; achado: Reference exige
      `${{...}}` — `${Redis.REDIS_PASSWORD}` não resolvia e caía em fail-open),
      failover/rollout real (derrubar 1 réplica = rollout substituindo réplicas
      sem downtime). Calibrado junto ao benchmark das Specs 59/60.
- [x] 10. Registrar valores finais no roadmap (2026-08-08):
      `numReplicas: 2`, `NYX_SHUTDOWN_TIMEOUT=30s` (default),
      `NYX_DB_POOL_SIZE=20` por réplica (2×20=40 < max_connections do
      Postgres), `JAVA_OPTS=-Xmx320m`.
