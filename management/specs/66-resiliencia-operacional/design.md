# Spec 66 — Design: Resiliência operacional pós-análise

## Contexto

- A Spec 63 fechou o edge Cloudflare, mas o **critério 9 (rollback)** ficou com
  achado: proxy OFF resolve o DNS para o Railway, porém o HTTPS direto falha
  (handshake reset) porque o CNAME aponta para o domínio default
  (`nyxcash-production.up.railway.app`) e o Railway não serve o cert de
  `api.foxcash.example.com` nesse IP. O custom domain JÁ está registrado no Railway
  (ACTIVE), mas o CNAME não usa o endpoint dele.
- O `SecurityAlertPublisher` (Spec 64) conta rajadas **por-instância**
  (`ConcurrentHashMap` local). Com N réplicas, o limiar pode não disparar para
  um flood distribuído — observado na verificação da Spec 63 (2 réplicas, 5
  bloqueios divididos → 0 alertas; só disparou com ≥5 numa única instância).
- O body inspection (Spec 63, camada app) tem monitor via logs
  (`ops/monitor-body-inspection.sh`) mas **sem métrica** no Prometheus e sem
  runbook formal de calibração.

## Decisões

### Fase A — Rollback de edge completo (config de infra, não código)

**Topologia alvo:**

```
Cliente → Cloudflare (proxy ON: WAF/RL/TLS) → api.foxcash.example.com (custom domain do
         Railway, cert válido) → edge Railway → app
Rollback (proxy OFF): Cliente → api.foxcash.example.com (custom domain do Railway, cert
         válido) → edge Railway → app   ← HTTPS volta a funcionar
```

Passos (documentar em `deploy/edge.md` §Rollback):
1. Verificar o CNAME de custom domain fornecido pelo Railway (o endpoint do
   custom domain — ex.: o Railway instrui apontar para um target próprio quando
   o domínio é adicionado; conferir via `railway domain` e dashboard).
2. Ajustar o CNAME `api.foxcash.example.com` no Cloudflare para o endpoint de custom
   domain do Railway (mantendo proxy ON).
3. **Testar rollback real**: proxy OFF → `curl -I https://api.foxcash.example.com`
   deve responder 200 direto (TLS válido, sem CF-Ray) → proxy ON (restaurar).
4. Atualizar `deploy/edge.md`: procedimento correto, evidência do teste,
   alternativa (manter proxy ON + usar o domínio default para acesso direto).

> Se o Railway não expuser um target de custom domain distinto (CNAME único),
> a alternativa documentada é manter proxy ON como primário e registrar no
> runbook que rollback = zona removida + nameservers de volta ao registrar (com
> a limitação de cert medida).

### Fase B — Alertas de segurança agregados (código)

**Nova porta:** `AlertCounterStore` (em `domain/port/out`) — espelha o padrão do
`RateLimitStore` (Spec 43):
```java
public interface AlertCounterStore {
    /** INCR e devolve o count na janela (TTL = windowSeconds). Chave: alert:{tipo}:{contexto}. */
    long incrementAndGet(String counterKey, long windowSeconds);
}
```

**Duas implementações selecionáveis** (`foxcash.security.alert.store`, env
`NYX_SECURITY_ALERT_STORE`, default `memory` — ADR-010):
- `InMemoryAlertCounterStore` (default) — comportamento atual
  (`ConcurrentHashMap` + janela), extraído do `SecurityAlertPublisher`.
- `RedisAlertCounterStore` — **script Lua atômico** `INCR`+`EXPIRE` (TTL =
  window), chave `alert:{tipo}:{contexto}`; **fail-open**: qualquer
  `RuntimeException` → WARN throttled + fallback para o contador local em
  memória (o alerta continua por-instância, nunca perde o bloqueio).

**Mudança no `SecurityAlertPublisher`:** o contador de rajada (`record(...)`)
deixa de usar o `CounterState` local diretamente e passa a usar a porta
(`incrementAndGet(counterKey, windowSeconds)`), mantendo:
- throttle global por tipo (`publishThrottled`, `lastAlertAt` por tipo — já
  global por instância; com Redis o limiar vira global, o throttle continua
  por-instância — aceitável: 1 alerta por réplica por janela, e o operador
  deduplica; documentar).
- contagem "fire once" ao atingir o limiar (zera/reinicia a janela do contador
  Redis com `SET ... EX` + `NX`/`DEL` no disparo — ver runbook de atomicidade
  abaixo).

**Atomicidade do disparo (Lua):**
```lua
-- alert_fire.lua: INCR no contador; se count == threshold, reseta a janela
-- (evita re-disparo imediato) e devolve 1; senão devolve 0.
local c = redis.call('INCR', KEYS[1])
if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
if c == tonumber(ARGV[1]) then
  redis.call('DEL', KEYS[1])
  return 1
end
return 0
```
(Script único `incrementAndFire(counterKey, threshold, windowSeconds)`; o
`SecurityAlertPublisher` chama o store e, se `1`, aplica o throttle por tipo e
publica — o throttle `lastAlertAt` continua em memória, por-instância, como
anti-spam adicional.)

**Config:** `foxcash.security.alert.store` (`memory`|`redis`) + reuso do
`StringRedisTemplate` (mesmo do rate limit; `spring.data.redis.*`).

### Fase C — Métrica e calibração do body inspection (código + docs)

**Métrica** no `RequestBodyInspectionFilter` (injeta `MeterRegistry`):
- `nyx.body_inspection.blocks` — `Counter` com tags `pattern` e `category`
  (incrementada no bloqueio, ANTES de responder 403) + `total` implícito pela
  soma das séries. Visível em `/actuator/prometheus` (ROLE_ADMIN).

**Runbook de calibração** (em `deploy/edge.md` §7.5 + §Fase C):
1. Coletar evidência: `ops/monitor-body-inspection.sh --json` + métrica
   `nyx.body_inspection.blocks` + logs (WARN por bloqueio, `X-Request-Id`).
2. Classificar: ataque real (padrão de bot/flood) vs falso positivo legítimo
   (ex.: campo de texto livre com combinação SQL em prosa).
3. Falso positivo → afinar o padrão específico (remover/refinar o regex) OU
   reversão imediata `NYX_BODY_INSPECTION_ENABLED=false` no Railway (sem
   redeploy de código), e registrar o padrão em `management/lessons.md`.
4. Ataque real → cruzar com edge (Cloudflare analytics), bloquear IP/ASN no
   WAF (Spec 63) e usar o alerta `BODY_INSPECTION_BLOCKED` (Spec 66 Fase B).

**Cron do monitor** (instrução no runbook): `*/15 * * * * ops/monitor-body-inspection.sh
>> /var/log/foxcash-body-inspection.log 2>&1` — exit 2 = houve bloqueio (hook de
alerta opcional).

## Arquivos

| Arquivo | Ação |
|---|---|
| `src/main/java/com/foxcash/domain/port/out/AlertCounterStore.java` | novo — porta |
| `src/main/java/com/foxcash/infrastructure/security/InMemoryAlertCounterStore.java` | novo — default |
| `src/main/java/com/foxcash/infrastructure/security/RedisAlertCounterStore.java` | novo — Lua atômico, fail-open |
| `src/main/java/com/foxcash/application/service/SecurityAlertPublisher.java` | mod — usa a porta (`incrementAndFire`) |
| `src/main/java/com/foxcash/infrastructure/config/SecurityAlertProperties.java` | mod — `store` (memory\|redis) |
| `src/main/resources/application.yml` | mod — `foxcash.security.alert.store` |
| `src/main/java/com/foxcash/infrastructure/security/RequestBodyInspectionFilter.java` | mod — métrica `nyx.body_inspection.blocks` |
| `src/main/java/com/foxcash/application/service/AlertCounterStoreConfig.java` | novo — bean condicional por store |
| `deploy/edge.md` | mod — §Rollback correto (Fase A) + §7.5 calibração (Fase C) |
| `ops/monitor-body-inspection.sh` | mod — sugestão de cron no header (Fase C) |
| Testes: `InMemoryAlertCounterStoreTest`, `RedisAlertCounterStoreTest` (mock Lua via MockWebServer ou store fake), `SecurityAlertPublisherTest` (adaptado à porta), `RequestBodyInspectionFilterTest` (+métrica) | novos/mod |

## Fora de escopo

PgBouncer/réplicas (ADR-013), DLQ física, step-up auth, Doppler/Vault, upgrade
Cloudflare — decisões registradas; não reabrir sem evidência.
