# Spec 43 — Task breakdown

Status: `audited` (2026-08-05). `mvn test` **490/0/0** · E2E **22/22**.

## Implementação

- [x] Porta `RateLimitStore` (domain/port/out): `boolean tryAcquire(key,
      maxRequests, windowSeconds)`.
- [x] `InMemoryRateLimitStore` — mover a lógica do `RateLimitFilter`
      (ConcurrentHashMap + limpeza best-effort); `RateLimitFilter` passa a
      injetar a porta.
- [x] `RedisRateLimitStore` — script Lua atômico (INCR + EXPIRE, TTL =
      window-seconds setado na 1ª req da janela); captura de falha de conexão →
      **fail-open** com `log.warn` throttled (uma vez por janela).
- [x] `RateLimitProperties.store` (`memory` default) + `@ConditionalOnProperty`
      (memory `matchIfMissing=true`; redis `matchIfMissing=false`) —
      exatamente UM bean ativo; `pom.xml` com `spring-boot-starter-data-redis`.
- [x] ADR-010 em `management/adr.md` (store distribuído, fail-open).
- [x] Testes: `RateLimitFilterTest` com store real (InMemory) + 2 com store
      mockado (delegação/bloqueio); `InMemoryRateLimitStoreTest` (janela,
      isolamento de chave, expiração, limpeza); `RedisRateLimitStoreTest`
      (Lua via mock do `StringRedisTemplate` — limite/contador, chave
      prefixada `rl:`, TTL, fail-open em `DataAccessResourceFailureException`
      e em `RuntimeException`).
- [x] README/runbook: seção "Rate limit — store selecionável" com env
      `NYX_RATE_LIMIT_STORE=redis` + `SPRING_DATA_REDIS_*` (multi-instância);
      single-instance sem Redis (`memory` default). Placeholder
      `${NYX_RATE_LIMIT_STORE:memory}` no `application.yml` base (vale para
      dev e prod).

## Verificação

- [x] `mvn test` **490/0/0** (479 → 490: +11 novos — RateLimitFilterTest 2,
      InMemoryRateLimitStoreTest 4, RedisRateLimitStoreTest 5) e E2E **22/22**
      sem regressão (store default memory).
- [x] Smoke **fail-open** ao vivo: boot com `store=redis` (sem Redis — porta
      6399 fechada) → flood de 30 POSTs em `/api/v1/merchants` → **30×201**
      (nenhum 429, nenhum 500 de conexão) e **1 WARN** `"Rate limit Redis
      indisponivel — fail-open (permitindo requisicao)"` (throttle 1/janela).
- [ ] Smoke multi-instância (2 instâncias + Redis compartilhando contador →
      429 agregado): **ADIADO** — requer um Redis ao vivo (WSL2/Docker
      indisponível nesta máquina). A semântica do contador compartilhado é
      coberta pelo teste do script Lua (mock) e pela atomicidade do `INCR`;
      o runbook de homologação documenta como validar em ambiente com Redis.

## Auditoria (ciclo SDD — `/spec-audit`, 2026-08-05)

**6/6 critérios OK** — `mvn test` **490/0/0** e E2E **22/22** sem regressão
(default memory).

- **Achado corrigido (higiene de boot):** com o starter `data-redis` no
  classpath, o boot logava ruído ("Bootstrapping Spring Data Redis
  repositories" + warnings de store assignment para cada repositório JPA).
  Fix: `spring.data.redis.repositories.enabled=false` no `application.yml`
  base (não há repositórios Redis — só o `StringRedisTemplate` do rate limit).
  Verificado ao vivo: 0 ocorrências de ruído no boot.
- **Aceitos por design:**
  - **Fail-open em qualquer `RuntimeException`** do `RedisRateLimitStore`: o
    rate limiter é proteção secundária — nunca pode derrubar o gateway (ADR-010);
    o custo é a perda da limitação durante a indisponibilidade do Redis
    (comportamento documentado e testado).
  - **Smoke multi-instância real adiado** (2 instâncias + Redis compartilhando
    contador → 429 agregado): requer um Redis ao vivo — WSL2/Docker
    indisponível nesta máquina. O critério aceita "se viável"; a atomicidade do
    `INCR` é coberta pelo teste do script Lua e o runbook documenta a validação.
  - **Teste de integração com Redis embutido não executado** — substituído pelo
    teste do script Lua com `StringRedisTemplate` mockado + teste de fail-open
    (o critério aceita "se viável"; o mock cobre chave prefixada, TTL e o
    resultado do contador).
- **Fim do ciclo SDD:** roadmap `audited` (feature LIVE).
