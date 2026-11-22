# Spec 61 — Design: escalabilidade horizontal e alta disponibilidade

## Contexto

- Auditoria de distributibilidade (2026-08-08): o app **já é stateless** —
  sessões DB-backed (`master_sessions`/`merchant_sessions`, token opaco 8h,
  `SessionCreationPolicy.STATELESS`), scheduler com ShedLock no Postgres
  (Specs 6/53), idempotência e locks pessimistas em banco (Specs 26/28/36).
- Bloqueios restantes:
  1. **Rate limit default `memory`** — contador por instância (ADR-010 registra
     o achado M-05; com réplicas, N× o limite efetivo para o mesmo cliente).
  2. **Deploy single-instance** — `railway.json` só tem
     `restartPolicyType: ON_FAILURE`; sem `replicas`, sem `healthcheckPath`;
     com 1 réplica não há rollout sem downtime.
- `application.yml`: sem `server.shutdown` (default `immediate` — corta
  requisições em voo no encerramento); o scheduler já tem
  `waitForTasksToCompleteOnShutdown` (Spec 53).
- Probes `/actuator/health/liveness` e `/actuator/health/readiness` já expostos
  (Spec 55) — falta conectá-los ao orquestrador (healthcheck do Railway).

## Decisões

1. **Graceful shutdown** (`application.yml`):

   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: ${NYX_SHUTDOWN_TIMEOUT:30s}
   ```

   O Tomcat para de aceitar novas conexões e drena as em voo até o timeout;
   o scheduler (Spec 53) encerra jobs em execução antes de o app parar. Env
   override (`NYX_SHUTDOWN_TIMEOUT`) para calibrar sem rebuild.

2. **Rate limit distribuído** — **sem código novo**: em multi-instância o
   operador seta `NYX_RATE_LIMIT_STORE=redis` (+ `SPRING_DATA_REDIS_*`). O
   `RedisRateLimitStore` (Spec 43/ADR-010) agrega os contadores de **todos**
   os grupos (IP/authenticated/transactional/webhook — Specs 18/58/59 usam o
   mesmo `RateLimitStore`) via script Lua atômico (`INCR` + `EXPIRE`),
   **fail-open** (indisponibilidade do Redis degrada a proteção, não derruba o
   serviço). Documentar como **obrigatório** no runbook HA (multi-instância
   sem Redis = rate limit por instância).

3. **Healthcheck + réplicas Railway** (`railway.json`):

   ```json
   {
     "$schema": "https://railway.app/railway.schema.json",
     "build": { "builder": "DOCKERFILE" },
     "deploy": {
       "replicas": 2,
       "healthcheckPath": "/actuator/health/readiness",
       "healthcheckTimeout": 300,
       "healthcheckInterval": 30,
       "restartPolicyType": "ON_FAILURE",
       "restartPolicyMaxRetries": 10,
       "restartPolicyDelay": 10
     }
   }
   ```

   **Baseline**: `replicas=2` (calibrar conforme custo/benchmark — Spec 60).
   O healthcheck aponta para **readiness** (não liveness): a instância só
   entra no balanço quando pronta (DB/Flyway OK); liveness fica para restart
   pelo orquestrador. **Na implementação, validar as chaves exatas contra o
   schema oficial do Railway** (`railway.app/railway.schema.json`).

4. **Verificação com 2 instâncias** — estender `docker-compose.yml` (hoje só
   Postgres):

   - service `redis` (redis:7-alpine);
   - serviços `api-a` e `api-b` — mesma imagem (build do Dockerfile), mesmo
     Postgres; env `SPRING_PROFILES_ACTIVE=dev` (secrets dev-only —
     crypto/admin/webhook), `DB_URL=jdbc:postgresql://postgres:5432/nyxcash`
     (base aponta p/ localhost), `NYX_RATE_LIMIT_STORE=redis` +
     `SPRING_DATA_REDIS_HOST=redis`; portas do host `8081:8080`/`8082:8080`
     (o app escuta em 8080 no yml base — Railway usa `$PORT` no profile railway);
   - cenários (critérios 1-6 do requirements):
     - **Sessão cross-instância**: login master na A → request com o token na
       B → 200 (sessão DB-backed).
     - **Scheduler 1×**: com 2 instâncias no ar, `job_runs` mostra 1 execução
       por job (ShedLock no Postgres — Specs 6/53).
     - **Rate limit agregado**: teto reduzido por env (ex.: 5/min) + requests
       alternando A/B → 429 no agregado (Redis).
     - **Idempotência cross-instância**: replay de cobrança/webhook alternando
       A/B → sem duplicidade (locks pessimistas em banco).
     - **Failover**: SIGTERM na A (graceful shutdown) → B responde 200;
       requisição em voo na A completa (dreno).

5. **Auditoria de estado em memória** — **confirmado (2026-08-08)** com busca
   no código (`HttpSession`, `@SessionAttributes`, `@Cacheable`/`CacheManager`,
   campos de instância mutáveis, `static` mutável, `AtomicReference`,
   `ConcurrentHashMap`):

   | Estado local | Comportamento com N instâncias | Ação |
   |---|---|---|
   | `InMemoryRateLimitStore` (default `store=memory`) | contador por instância — N× o limite efetivo | trocar por `store=redis` em multi-instância (dec. 2) |
   | Sessões master/portal | DB-backed (`master_sessions`/`merchant_sessions`) — já distribuído | nenhuma |
   | Scheduler ShedLock | lock no Postgres — execução única | nenhuma |
   | Idempotência/locks | banco (índice único + `FOR UPDATE`) — já distribuído | nenhuma |
   | `WebhookSignatureFilter.lastInvalidSignatureLog` (`AtomicReference<Instant>`, Spec 59) | throttle de WARN por instância — não afeta contrato (401 mantido) | nenhuma (aceitável/desejável: só corta spam de log) |
   | `MockPixProviderClient.createdByTransaction`/`timedOutTransactions` | estado por instância — **apenas dev/testes** (`provider=mock`); produção usa adquirente real (pagarme/asaas) | nenhuma (fora de produção) |

   **Ausências confirmadas:** sem `HttpSession`/`@SessionAttributes` (sessões
   DB-backed, `SessionCreationPolicy.STATELESS`); sem `@Cacheable`/
   `CacheManager`; sem campos `static` mutáveis (apenas `final` imutáveis e
   métodos estáticos); os `new HashMap<>()` encontrados são locais de método
   (transientes). Único estado relevante para N instâncias: o
   `InMemoryRateLimitStore` (trocado por Redis).

6. **Runbook HA** (`deploy/railway.md`): nova seção — réplicas, healthcheck,
   env vars obrigatórias em multi-instância (`NYX_RATE_LIMIT_STORE=redis`,
   `SPRING_DATA_REDIS_*`, `NYX_SHUTDOWN_TIMEOUT`), `NYX_DB_POOL_SIZE` por
   réplica (Spec 60: o pool é compartilhado no mesmo Postgres — somar folga
   por réplica, respeitando `max_connections` do Railway), rollout/rollback e
   verificação pós-deploy (health, métricas Prometheus).

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/resources/application.yml` | `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` |
| `railway.json` | `replicas` + `healthcheckPath`/`healthcheckTimeout`/`healthcheckInterval` |
| `docker-compose.yml` | `redis` + 2 instâncias `api-a`/`api-b` (verificação local) |
| `deploy/railway.md` | seção HA + env vars multi-instância + rollout/rollback |
| `management/specs/61-horizontal-scalability-ha/` | documentos da spec (este) |

## Fora de escopo

- Banco de dados HA (replicação/failover do Postgres) — o Postgres Railway é
  o ponto único; replicação de DB é infra gerenciada pelo Railway/Plano,
  fora desta spec (registrar como observação pós-auditoria se necessário).
- Orquestração além do Railway (K8s etc.) — topologia alvo é o Railway.
- Mudança de contrato de API — nenhuma.
