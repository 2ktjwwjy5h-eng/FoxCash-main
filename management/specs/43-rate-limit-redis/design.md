# Spec 43 — Design: Rate limit distribuído com Redis (M-05)

## Contexto

O `RateLimitFilter` (Spec 18) usa janela fixa em `ConcurrentHashMap` por
IP+rota — correto para instância única (VPS), mas cada instância teria o seu
próprio contador (N× o limite efetivo em multi-instância). O achado M-05 da
homologação documenta a limitação. Esta spec adiciona um **store Redis**
selecionável sem quebrar a topologia atual.

## Decisões de arquitetura

### 1. Porta `RateLimitStore` + duas implementações

- `domain/port/out/RateLimitStore`: `boolean tryAcquire(String key, int
  maxRequests, long windowSeconds)` — semântica de janela fixa (conta na
  janela corrente; excedeu o limite → `false`).
- `InMemoryRateLimitStore` — extrai a lógica atual do `RateLimitFilter`
  (janela por `ConcurrentHashMap` + limpeza best-effort). Default.
- `RedisRateLimitStore` — script Lua atômico:

  ```lua
  local key = KEYS[1]
  local limit = tonumber(ARGV[1])
  local ttl = tonumber(ARGV[2])
  local current = redis.call('INCR', key)
  if current == 1 then redis.call('EXPIRE', key, ttl) end
  return current > limit
  ```

  (janela fixa: chave = `rl:{ip}|{rota}`; TTL = `window-seconds` — mesma
  janela da versão em memória).

- Seleção por `@ConditionalOnProperty(name = "foxcash.security.rate-limit.store",
  havingValue = "redis", matchIfMissing = false)` no bean Redis; o filtro
  injeta a porta e ignora o store concreto. `spring-boot-starter-data-redis`
  adicionado ao `pom.xml` (dependência nova só carregada com o store).

### 2. Comportamento com Redis indisponível

- `RedisRateLimitStore.tryAcquire` captura exceção de conexão e **fail-open**
  (retorna `true` — permite a requisição) com `log.warn` uma vez por janela
  (rate-limited no log). Justificativa: o rate limit é proteção secundária;
  o gateway de pagamento não pode ficar indisponível por falha de infra
  auxiliar. Documentado na ADR nova.

### 3. Config

- `RateLimitProperties` ganha `store` (`memory` default).
- `application-prod.yml`/README: `NYX_RATE_LIMIT_STORE=redis` +
  `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` para multi-instância; single-instance
  segue sem Redis (nenhuma env nova obrigatória).

### 4. Testes

- Unit: `RateLimitFilterTest` com store mockado (mesmo comportamento 429);
  `InMemoryRateLimitStoreTest` (janela, limpeza); `RedisRateLimitStoreTest`
  com `@Testcontainers`/Redis embutido quando disponível, senão teste do
  script Lua com mock do `RedisTemplate` (INCR/EXPIRE) + teste de fail-open.
- E2E 22/22 sem regressão (default memory).

## ADR

Nova entrada em `management/adr.md`: **ADR-010 — store distribuído do rate
limit (Redis, fail-open)** — contexto (M-05), decisão (porta + store
selecionável, Lua atômico, fail-open), consequências (dependência Redis só em
multi-instância; perda de limitação durante indisponibilidade do Redis é
aceita).

## Arquivos (visão geral)

- `domain/port/out/RateLimitStore` (porta)
- `infrastructure/security/InMemoryRateLimitStore`, `RedisRateLimitStore`
- `infrastructure/security/RateLimitFilter` (injeta a porta)
- `infrastructure/config/RateLimitProperties` (`store`)
- `pom.xml` (starter data-redis)
- `management/adr.md` (ADR-010)
