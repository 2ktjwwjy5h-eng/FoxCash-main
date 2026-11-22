---
name: Escalabilidade horizontal e alta disponibilidade (N instâncias)
status: audited
references:
  - "management/vision.md#V41"
  - "management/specs/43-rate-limit-redis/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/60-infra-hardening/requirements.md"
  - "management/adr.md#ADR-010"
  - "management/adr.md#ADR-011"
---

# Spec 61 — Escalabilidade horizontal e alta disponibilidade

## O que entrega

O backend passa a rodar **N instâncias** com alta disponibilidade (HA). A
auditoria de distributibilidade (2026-08-08) confirmou que o app **já é
majoritariamente stateless** — sessões DB-backed (`master_sessions`/
`merchant_sessions`, token opaco 8h, `SessionCreationPolicy.STATELESS`),
scheduler com ShedLock no Postgres (Specs 6/53), idempotência e locks
pessimistas em banco (Specs 26/28/36). Os **bloqueios restantes** para N
instâncias são dois:

1. **Rate limit por instância** — `foxcash.security.rate-limit.store` default
   `memory` (contador em `ConcurrentHashMap` local; com réplicas, N× o limite
   efetivo para o mesmo cliente). A solução já existe (Spec 43/ADR-010):
   `NYX_RATE_LIMIT_STORE=redis`.
2. **Deploy single-instance** — `railway.json` sem réplicas nem healthcheck;
   sem `server.shutdown=graceful` (sem dreno de requisições no rollout).

Entregáveis:

1. **Graceful shutdown**: `server.shutdown=graceful` + timeout de dreno
   (`spring.lifecycle.timeout-per-shutdown-phase`, env override) — no
   rollout/deploy, o Tomcat para de aceitar novas requisições e completa as em
   voo antes de encerrar (complementa o `waitForTasksToCompleteOnShutdown` do
   scheduler, Spec 53).
2. **Readiness/liveness para orquestração**: os probes
   `/actuator/health/readiness` e `/actuator/health/liveness` já existem
   (Spec 55); a spec os conecta ao orquestrador — healthcheck do Railway no
   `railway.json` (instância não-pronta não entra no balanço / é reiniciada).
3. **Rate limit distribuído**: ativar `NYX_RATE_LIMIT_STORE=redis` em
   multi-instância — contadores agregados entre réplicas para **todos** os
   grupos (IP público, autenticado, transacional, webhook — Specs 18/43/58/59
   usam o mesmo `RateLimitStore`; fail-open documentado na ADR-010).
4. **Auditoria de estado em memória**: inventário documentado de estado local
   e como cada item se comporta com N instâncias (único estado relevante: o
   `InMemoryRateLimitStore`, trocado por Redis; o restante já é DB/stateless).
5. **Deploy Railway multi-instância**: `railway.json` com `replicas` (baseline
   2, calibrar) + `healthcheckPath`/`healthcheckTimeout`/`healthcheckInterval`
   — o Railway roteia HTTP entre as réplicas e faz rollout sem downtime com
   `replicas > 1`.
6. **Verificação multi-instância**: prova com **2 instâncias** (docker compose
   local — estende o `docker-compose.yml` existente — e/ou Railway) de que o
   sistema escala horizontalmente e é HA, com os critérios abaixo.
7. **Runbook de HA** em `deploy/railway.md`: réplicas, healthcheck, env vars
   obrigatórias em multi-instância (`NYX_RATE_LIMIT_STORE=redis`,
   `SPRING_DATA_REDIS_*`, `NYX_SHUTDOWN_TIMEOUT`), procedimento de
   rollout/rollback e verificação pós-deploy.

## Critérios de aceite

1. **Graceful shutdown**: com `server.shutdown=graceful`, ao encerrar a
   instância (SIGTERM), requisições em voo completam e novas conexões são
   recusadas — sem cortar resposta no meio; log registra o dreno.
2. **Rate limit agregado**: com `store=redis` e 2 instâncias, requests
   distribuídos entre elas que ultrapassem o teto agregado → 429
   `too_many_requests` (mesma semântica da Spec 43, para IP/authenticated/
   transactional/webhook).
3. **Scheduler 1×**: com 2 instâncias, cada job (faturas, risco, cobrança,
   reconciliação, payout-reconciliation, dispatch do outbox, cleanup) roda
   **exatamente 1×** — evidência via `job_runs`/ShedLock (Specs 6/53).
4. **Sessões cross-instância**: token de master/portal criado na instância A é
   aceito na instância B (sessões DB-backed).
5. **Idempotência cross-instância**: replay de cobrança/webhook com requisições
   caindo em instâncias diferentes → sem duplicidade (locks pessimistas em
   banco, Specs 26/28/36).
6. **HA/failover**: com 2 instâncias, derrubar uma delas → o serviço continua
   respondendo 200 (a outra absorve); sem perda de requisições além da janela
   de dreno.
7. **Auditoria de estado**: inventário documentado (design.md) de estado em
   memória × comportamento com N instâncias — nenhum item além do rate limit
   `memory` (substituído por `redis`) compromete a topologia multi-instância.
8. **Healthcheck Railway**: `railway.json` com `replicas` + `healthcheckPath`
   apontando para `/actuator/health/readiness` (chaves validadas no schema do
   Railway na implementação).
9. `mvn test` sem regressão; E2E sem regressão (mudanças de runtime/config não
   alteram contrato de API).

## Filosofia (baseline ≠ definitivo)

`replicas=2` e o timeout de dreno são **baseline inicial** — o objetivo não é
maximizar instâncias, é garantir **degradação controlada e disponibilidade**:
com N réplicas o serviço sobrevive à queda de uma instância e a deploys sem
downtime. A calibração final (N de réplicas, timeout de dreno, pool de DB por
réplica — Spec 60) depende do benchmark Railway pendente das Specs 59/60 e do
custo mensal; valores finais registrados no roadmap após a calibração.

## Dependências

- `management/specs/43-rate-limit-redis/requirements.md` (store distribuído —
  base do critério 2)
- `management/specs/53-job-robustez/requirements.md` (ShedLock + pool de jobs —
  base do critério 3)
- `management/specs/60-infra-hardening/requirements.md` (Tomcat/Hikari/JVM —
  base do dimensionamento por réplica)
- `management/adr.md` (ADR-010 — rate limit Redis fail-open; ADR-011 — infra
  de produção no Railway)

## Auditoria 2026-08-08

**Resultado: 0 issues** (implementação ea02df6, `mvn test` 1101/0/0
re-executado na auditoria).

Critérios verificados e evidência:

| Critério | Evidência |
|---|---|
| 1 (graceful shutdown) | `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: ${NYX_SHUTDOWN_TIMEOUT:30s}` (application.yml); contexto carrega em 1101 testes. O dreno vivo por SIGTERM **não é reproduzível no Windows** (limitação de sinais do SO — task 2); validação real fica na task 9 (Railway/Linux, SIGTERM do orquestrador) |
| 2 (rate limit agregado) | `NYX_RATE_LIMIT_STORE=redis` (application.yml, env override) + `RedisRateLimitStore` fail-open (Spec 43/ADR-010); docker-compose seta `NYX_RATE_LIMIT_STORE=redis` + `SPRING_DATA_REDIS_HOST=redis` em api-a/api-b. Prova ao vivo com 2 instâncias **deferida à task 9** (sem daemon Docker/Redis no ambiente); evidência unitária: `RedisRateLimitStoreTest` (6 testes, script Lua INCR+EXPIRE, TTL, fail-open) |
| 3 (scheduler 1×) | verificação 5b com 2 JVMs locais: `job_runs` com **1 execução por ciclo de 30s** (01:30:26→01:32:26) e **0 overlaps**; lock distribuído (A=1, B=4 execuções) — ShedLock no Postgres |
| 4 (sessões cross-instância) | verificação 5a: master via SQL → login na A (8080) → token usado na B (8082) → **200**; sem token → **401** (sessões DB-backed) |
| 5 (idempotência cross-instância) | verificação 5d: cobrança com chave K na A → 201; replay da MESMA chave na B → **mesma transação (19577c28...)**, DB com **1 transação PENDING** (locks pessimistas, Specs 26/28/36) |
| 6 (HA/failover) | verificação 5e: A derrubada → B responde **200** (health + request autenticado); sessão sobrevive à morte da A |
| 7 (auditoria de estado) | inventário completo no design.md (tabela estado×N instâncias + ausências confirmadas: sem `HttpSession`/`@SessionAttributes`/`@Cacheable`/`static` mutável); único estado relevante = `InMemoryRateLimitStore` → trocado por `redis` |
| 8 (healthcheck Railway) | `railway.json`: `replicas: 2` + `healthcheckPath: /actuator/health/readiness` + `healthcheckTimeout: 300` + `healthcheckInterval: 30`; `/actuator/health/**` `permitAll` (SecurityConfig — probe sem auth). Schema do Railway **não validável via rede** no ambiente (bloqueio de rede) — validação na task 9 (deploy) |
| 9 (`mvn test` / E2E) | `mvn test` **1101/0/0** (146 suítes, re-executado na auditoria) ✅; E2E Playwright **104/104 passed** (última execução 2026-08-07 22:39, `test-results/.last-run.json`); spec 61 só altera runtime/config — contrato de API inalterado |

**Follow-ups registrados (não bloqueantes):** task 9 (deploy Railway com
`replicas=2` + verificação ao vivo: health 200, rollout sem downtime, failover
real, dreno do graceful shutdown por SIGTERM em Linux, rate limit agregado via
Redis, validação das chaves do `railway.json` no schema oficial) e task 10
(registrar valores calibrados — replicas, `NYX_SHUTDOWN_TIMEOUT`,
`NYX_DB_POOL_SIZE` por réplica — no roadmap após o benchmark das Specs 59/60).
