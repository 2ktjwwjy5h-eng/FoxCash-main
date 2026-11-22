---
name: Log drain e agregação de logs
status: audited
---

# Spec 95 — Design: Log drain e agregação de logs

## Estado atual (não refazer)

| Capacidade | Onde | Status |
|---|---|---|
| Log estruturado em prod | `logback-spring.xml` (`ts/level/logger/requestId/traceId/spanId/msg`, 1 linha/evento) | ✅ existe |
| `X-Request-Id` no MDC | `RequestIdFilter` (valida/genera + header de resposta) | ✅ existe |
| `traceId`/`spanId` | Micrometer Tracing (Spec 57) | ✅ existe |
| Métricas Prometheus | `/actuator/prometheus` (Spec 53) | ✅ existe |
| Trilha `platform_activity_log` | Spec 41 — append-only, índice `ix_platform_activity_created` | ✅ existe |
| Runbook de incidente | `ops/security-incident.md` (Spec 64) | ✅ existe |
| **Log drain externo** | — | ❌ **lacuna** |
| **Doc de consulta/correlação** | — | ❌ **lacuna** |
| **Retenção da trilha** | — | ❌ **lacuna (opt-in)** |

## Entregáveis

### 1. Docs — `ops/log-drain.md` (runbook) + seção no `deploy/railway.md`

Estrutura do runbook:

1. **Onde os logs vivem**: aba *Logs* do serviço no Railway (janela de retenção
   da plataforma anotada — free tier limitada; consulta via UI ou CLI `railway
   logs`).
2. **Log drain** (passo a passo Railway → Project → Observability → Log drain):
   destino Axiom/Loki **free tier**; a credencial do destino fica no Railway,
   **nunca no repo/git** (nenhuma env var de segredo nova no código).
3. **Busca por `X-Request-Id`**: o RequestIdFilter injeta o id no log
   (`requestId=...` no padrão estruturado) e no header de resposta — o operador
   copia o id da resposta da API e filtra no agregador.
4. **Correlação**:
   - Log estruturado: `requestId=X` → achar `ts`/`logger` do evento;
   - `platform_activity_log`: `SELECT ... WHERE created_at BETWEEN ... AND
     action='MFA_LOGIN_SUCCESS' AND actor_id='...'` (cruzar pelo `actor`/`action`
     e pela janela de tempo do `requestId`);
   - Métricas: `/actuator/prometheus` (`nyx_*` counters) na mesma janela.
5. **Privacidade**: os logs já passam pelo `SensitiveDataLoggingFilter` (sem
   senha/token/segredo/documento/email em claro) — o drain herda essa garantia;
   instrução explícita de não adicionar dados sensíveis em logs futuros.

`deploy/railway.md`: nova seção **"4b. Log drain (opcional)"** — passo a passo
resumido + ponteiro para `ops/log-drain.md`.

### 2. Código — job de retenção opt-in (padrão Spec 53)

Espelho do `JobRunsCleanupScheduler` + condicional do `PspSweepScheduler`
(`@ConditionalOnProperty` — default OFF preserva o contrato append-only da
Spec 41).

**Porta** — `PlatformActivityRepository` ganha:

```java
int deleteOlderThan(Instant cutoff);
```

**JPA** — `PlatformActivityJpaRepository`:

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("delete from PlatformActivityJpaEntity a where a.createdAt < :cutoff")
int deleteOlderThan(@Param("cutoff") Instant cutoff);
```

(usa o índice `ix_platform_activity_created` existente — V33).

**Adapter** — `PlatformActivityPersistenceAdapter.deleteOlderThan` delega ao JPA.

**Scheduler** — `PlatformActivityCleanupScheduler`:

```java
@Component
@ConditionalOnProperty(prefix = "foxcash.scheduler",
        name = "activity-log-cleanup-enabled", havingValue = "true")
public class PlatformActivityCleanupScheduler {

    @Scheduled(cron = "${foxcash.scheduler.activity-log-cleanup-cron:0 15 4 * * *}",
            zone = "UTC")
    @SchedulerLock(name = "foxcash-activity-log-cleanup",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        jobs.run("foxcash-activity-log-cleanup", () -> {
            Instant cutoff = Instant.now()
                    .minus(Duration.ofDays(properties.getActivityLog().getRetentionDays()));
            int deleted = activityRepository.deleteOlderThan(cutoff);
            log.info("Limpeza de platform_activity_log: {} registro(s) anteriores a {} removidos",
                    deleted, cutoff);
        });
    }
}
```

**Config** — `SchedulerProperties` ganha `ActivityLog activityLog`:

```yaml
foxcash:
  scheduler:
    activity-log-cleanup-enabled: false   # default OFF (append-only preservado)
    activity-log-cleanup-cron: "0 15 4 * * *"
    activity-log:
      retention-days: 180
```

### 3. Teste

`PlatformActivityCleanupSchedulerTest` (Mockito, espelho do padrão dos testes de
serviço): com o job ativo, `cleanup()` chama `deleteOlderThan(cutoff)` com
`cutoff = now - 180d` e registra `foxcash-activity-log-cleanup` no
`JobExecutionService` (mock com `doAnswer` executando o Runnable).

### 4. Aditivo pós-auditoria (commit `b9cf789`) — appender Axiom direto

Além do drain da plataforma (§2 do runbook, opção A), o backend ganhou a
**opção B**: **`AxiomHttpAppender`** em `infrastructure/logging/` — appender
Logback HTTP assíncrono que serializa cada evento com `LogstashEncoder` (MDC
`requestId`/`traceId`/`spanId` + `service=foxcash`) e POSTa lotes no ingest do
Axiom.

**Design (fail-open — logs NUNCA derrubam/bloqueiam a aplicação):**

- **Assíncrono**: `ArrayBlockingQueue` (default 2000) + thread daemon
  (`axiom-log-drain`); `append()` só enfileira (`offer`) — sem I/O no thread do
  request. O MDC é capturado via `prepareForDeferredProcessing()` no thread que
  logou (o worker é outra thread).
- **Fila cheia**: descarta + WARN via `StatusManager` (1º e a cada 1000
  descartes) — o request não espera.
- **Axiom fora / HTTP ≠ 2xx**: WARN (corpo truncado a 200 chars) e segue — o
  lote é perdido, a aplicação não.
- **Config ausente** (sem `url`/token): `start()` falha aberto — appender
  desligado, app sobe.
- **Warnings via `StatusManager.addWarn`** — NUNCA via SLF4J/logger, para não
  realimentar o próprio appender (loop infinito).
- **Token**: env `AXIOM_API_TOKEN` (Railway variable, nunca no repo) ou atributo
  `<token>` do logback; o token nunca é logado.

**Wiring** (`logback-spring.xml`, profile `prod`): appender `AXIOM` com
`url=https://api.axiom.co/api/v1/datasets/FoxCash/ingest` (região: `api`/`eu`
conforme a conta), `token=${AXIOM_API_TOKEN:-}`, `maxQueueSize=2000`,
`batchSize=50`, encoder Logstash com `includeMdc=true` +
`customFields={"service":"foxcash"}`. Sem `AXIOM_API_TOKEN` no ambiente, o
appender fica desligado (fail-open) — o console estruturado segue funcionando.

**Dependência** (`pom.xml`): `net.logstash.logback:logstash-logback-encoder`
(provedor do `LogstashEncoder`).

**Teste** (`AxiomHttpAppenderTest`): (1) lote JSON com Bearer token + MDC via
MockWebServer (2 eventos → 1 POST, `Authorization: Bearer`, `requestId` no
JSON); (2) fail-open com token ausente (`isStarted() == false`, `append()` é
no-op).

## Decisões registradas

- **D1** O drain da plataforma **não é configurado nesta spec** — é passo
  operacional no Railway; a spec entrega o runbook completo do passo a passo.
  **Aditivo:** o appender Axiom direto (opção B) ENTRA no código como
  alternativa independente do Railway (fail-open) — escolher UMA das duas para
  não duplicar eventos (documentado em `ops/log-drain.md` §2.3).
- **D2** Retenção **opt-in (default OFF)** — o `platform_activity_log` é
  append-only por contrato (Spec 41); o job é ferramenta de operador, habilitada
  por config quando o volume exigir. Nenhuma rota de API de mutação é criada.
- **D3** O formato de log permanece estruturado em prod; o appender Axiom
  usa `LogstashEncoder` sobre o MESMO evento (não muda o padrão do console —
  compatível com Loki/Datadog/ELK via drain).
- **D4** Sem migration nova — o índice `ix_platform_activity_created` já existe
  (V33).
