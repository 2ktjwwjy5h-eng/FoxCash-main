---
name: Log drain e agregação de logs
status: audited
---

# Spec 95 — Task: Log drain e agregação de logs

## Docs

- [x] `ops/log-drain.md` (novo runbook): onde os logs vivem no Railway (janela de
      retenção), habilitação do **log drain** (Axiom/Loki free tier — credencial
      fora do repo), **busca por `X-Request-Id`** no agregador, **correlação**
      com `platform_activity_log` (SQL) e métricas (`/actuator/prometheus`),
      nota de privacidade (`SensitiveDataLoggingFilter`).
- [x] `deploy/railway.md` — seção nova **"4b. Log drain (opcional)"** (passo a
      passo resumido + ponteiro para o runbook).

## Aditivo pós-auditoria (commit `b9cf789`) — appender Axiom direto

- [x] `infrastructure/logging/AxiomHttpAppender.java` — appender HTTP
      assíncrono (fila `ArrayBlockingQueue` 2000 + thread daemon, batch 50) que
      serializa cada evento com `LogstashEncoder` (MDC `requestId`/`traceId`/
      `spanId`) e POSTa lotes no ingest do Axiom. **Fail-open**: sem token/url a
      app sobe com o appender desligado; fila cheia/Axiom fora → descarta com
      WARN (via StatusManager, sem realimentar o próprio appender). Token nunca
      no repo — env `AXIOM_API_TOKEN` (ou atributo `<token>`).
- [x] `src/main/resources/logback-spring.xml` — appender `AXIOM` no profile
      `prod` (`url` dataset `FoxCash`, token `${AXIOM_API_TOKEN:-}`, encoder
      Logstash com `service=foxcash`).
- [x] `pom.xml` — dependência `net.logstash.logback:logstash-logback-encoder`
      (provedor do `LogstashEncoder`).
- [x] `AxiomHttpAppenderTest` — lote JSON com Bearer token + MDC (MockWebServer)
      e fail-open com token ausente.
- [x] `mvn test` verde (suíte completa).

## Código — retenção opt-in (padrão Spec 53)

- [x] `domain/port/out/PlatformActivityRepository.java` — método
      `int deleteOlderThan(Instant cutoff);`.
- [x] `infrastructure/outbound/persistence/repository/PlatformActivityJpaRepository.java`
      — `@Modifying` + `@Query("delete ... where a.createdAt < :cutoff")`
      `deleteOlderThan(@Param("cutoff") Instant cutoff)`.
- [x] `infrastructure/outbound/persistence/adapter/PlatformActivityPersistenceAdapter.java`
      — implementar `deleteOlderThan` delegando ao JPA.
- [x] `infrastructure/config/SchedulerProperties.java` — nested
      `ActivityLog { int retentionDays = 180; }` + getters/setters.
- [x] `infrastructure/scheduler/PlatformActivityCleanupScheduler.java` — novo:
      `@Component` + `@ConditionalOnProperty(prefix="foxcash.scheduler",
      name="activity-log-cleanup-enabled", havingValue="true")` + `@Scheduled`
      (cron default `0 15 4 * * *`, UTC) + `@SchedulerLock`
      (`foxcash-activity-log-cleanup`) + `JobExecutionService.run` deletando
      via `deleteOlderThan(now - retentionDays)`.

## Testes

- [x] `src/test/java/com/foxcash/application/service/PlatformActivityCleanupSchedulerTest.java`
      — Mockito: cutoff = now − 180d passado a `deleteOlderThan`; job registrado
      no `JobExecutionService` (doAnswer executa o Runnable); contagem logada.
- [x] `mvn test` verde (sem regressão — job desligado por default).

## Docs + roadmap

- [x] `management/vision.md` — bloco **V71** (via `/spec-plan`).
- [x] `management/roadmap.md` — Spec 95 `proposed` → `planned` (narrativa do
      `/spec-plan`); ao final → `implemented`/`audited`.
- [x] Commit final com mensagem descritiva.
