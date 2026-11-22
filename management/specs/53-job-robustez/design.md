# Spec 53 — Design: Robustez da execução de jobs

## Contexto

Os 6 jobs do backend (schedulers) rodam com `@Scheduled` + ShedLock (Spec 6), mas: (1) o
scheduler usa o pool default do Spring (**1 thread**) — o outbox (poll 30s)
compartilha a thread dos crons; (2) nenhum job registra métrica (Micrometer no
classpath sem uso); (3) falha de job propaga para o Spring, que loga um ERROR
solto — **sem métrica, sem trilha, sem alerta**; (4) não há registro de
execuções para auditoria. Esta spec adiciona a infraestrutura de execução
robusta sem mudar o contrato dos jobs nem a topologia (in-process).

## Decisões de arquitetura

### 1. Pool de threads dedicado (`ThreadPoolTaskScheduler`)

- Bean em `SchedulingConfig`: `ThreadPoolTaskScheduler` com
  `poolSize = foxcash.scheduler.pool-size` (default 4),
  `threadNamePrefix = "nyx-job-"` e `waitForTasksToCompleteOnShutdown = true`
  (graceful shutdown — não aborta um job no meio em deploy).
- **Um pool único** (não 2 pools cron/outbox): os crons não colidem em
  horário; o problema real é o outbox ficar refém de cron longo — 4 threads
  resolvem com folga. 2 pools só se o isolamento estrito for exigido (fora de
  escopo).
- Config: `foxcash.scheduler.pool-size` em `SchedulerProperties` (novo prefixo
  `foxcash.scheduler` já existe para crons — reutilizar a classe ou criar uma
  propriedade no `PspProperties`? **Não** — criar `SchedulerProperties` com
  `poolSize` e `jobRuns.retentionDays`, prefixo `foxcash.scheduler`).

### 2. Abstração única de execução — `JobExecutionService`

Componente em `application/service` (orquestra métrica + trilha + alerta):

```java
public class JobExecutionService {
    private final MeterRegistry meterRegistry;
    private final JobRunRepository jobRuns;          // novo port/out + adapter JPA
    private final WebhookEventPublisher eventPublisher; // Spec 35 (reuso)
    private final SchedulerProperties properties;

    /** Executa, cronometra, registra métrica/trilha e captura falha. */
    public void run(String jobName, Runnable task) {
        long started = System.nanoTime();
        Instant startedAt = Instant.now();
        try {
            task.run();
            record(jobName, startedAt, started, null);
        } catch (Exception e) {
            record(jobName, startedAt, started, e);
            publishJobFailed(jobName, startedAt, e); // outbox (se configurado)
            // NÃO re-propaga: job agendado não deve derrubar a thread do pool.
        }
    }
}
```

- **Métricas** (Micrometer):
  - `Timer` `nyx.job.duration` (→ `nyx_job_duration_seconds` no Prometheus)
    com tag `job` (e `status`).
  - `Counter` `nyx.job.success` / `nyx.job.failure` (→ `nyx_job_success_total` /
    `nyx_job_failure_total` no Prometheus) tag `job` — **sem sufixo `_total`
    no nome Micrometer** (o Prometheus o anexa; senão gera `_total_total`).
- **Trilha** `job_runs` (V43): grava SUCCESS/FAILED com `duration_ms` e
  `error` (truncado, ex.: 500 chars — não vazar segredo/stack completa).
- **Alerta** (ver §5): publica `JOB_FAILED` no outbox com payload
  `{job, startedAt, error}` — sem merchant (`merchantId = null`).
- **Log estruturado**: sucesso `level=INFO job= status=SUCCESS duration_ms=`;
  falha `level=ERROR job= status=FAILED duration_ms= error=` (formato do
  logback estruturado de 2026-08-07).
- Os 6 schedulers refatoram para `jobExecutionService.run("<nome>", this::...)`
  e **perdem o try/catch/log individual** (nenhum tinha hoje).

### 3. `job_runs` — migration V43 + port/repositório

```sql
CREATE TABLE job_runs (
    id          BIGSERIAL PRIMARY KEY,
    job         VARCHAR(64)  NOT NULL,
    started_at  TIMESTAMP    NOT NULL,
    finished_at TIMESTAMP    NOT NULL,
    duration_ms BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
    error       TEXT
);
CREATE INDEX ix_job_runs_job_started ON job_runs (job, started_at DESC);
```

- `JobRunRepository` (port/out) + `JobRunJpaRepository`/adapter JPA (padrão
  dos demais repositórios).
- **Limpeza**: `JobRunsCleanupScheduler` diário (cron
  `foxcash.scheduler.job-runs-cleanup-cron`, default 04:45 UTC — após os
  crons), ShedLock `foxcash-job-runs-cleanup`, `lockAtMostFor=PT10M`; DELETE
  por `started_at < now() - retention-days` (default 30). O cleanup também
  delega ao `JobExecutionService` (`run("job-runs-cleanup", ...)`) — sua
  própria falha entra na métrica/trilha/alerta como qualquer job.

### 4. Métricas — exposição

- Nenhuma mudança de segurança: `/actuator/prometheus` já existe e exige
  `ROLE_ADMIN` (observabilidade 2026-08-07). Só registrar as métricas via
  `MeterRegistry` injetado no `JobExecutionService`.
- Nomes (visão Prometheus): `nyx_job_duration_seconds`,
  `nyx_job_success_total`, `nyx_job_failure_total` — registrados no Micrometer
  como `nyx.job.duration` / `nyx.job.success` / `nyx.job.failure` (convenção
  `nyx_*` do projeto; ver §2 para o naming de counters).

### 5. Alerta via outbox — `JOB_FAILED` (plataforma)

O outbox (Spec 35) é **merchant-scoped** (`notification_outbox.merchant_id`
NOT NULL, FK V4). `JOB_FAILED` é evento de **plataforma** (sem merchant). Para
reusar o pipeline (dispatcher 30s, retry/backoff, assinatura HMAC, dedup por
`eventId`):

- **Migration V43** (mesma): `ALTER TABLE notification_outbox ALTER COLUMN
  merchant_id DROP NOT NULL;` — mantém a FK (eventos de merchant intactos).
- `WebhookEventType.JOB_FAILED` novo enum.
- `WebhookEventPublisher.publish` aceita `merchantId == null` (payload
  `{eventId, type, merchantId: null, createdAt, data}`); o outbox salva com
  `NULL`.
- **Entrega**: `MerchantNotifier.deliver` hoje resolve a URL do webhook do
  merchant. Para evento de plataforma, o destino é o **webhook de operador**:
  `foxcash.notification.operator-webhook-url` + `operator-webhook-secret`
  (novos campos em `NotificationProperties`). No `NotificationProcessor`
  (ou no notifier), se `merchantId == null` → entrega ao webhook de operador
  com `X-FoxCash-Signature` (HMAC do secret do operador — o header do webhook
  de saída, Spec 35; `X-Psp-Signature` é o header do webhook de ENTRADA do
  PSP); **sem URL configurada →
  o evento nem é publicado** (o `JobExecutionService` checa
  `properties.isOperatorWebhookConfigured()` antes de publicar — fail-open
  operacional, alerta base = métrica + log + `job_runs`).
- **Payload** do `JOB_FAILED`: `{job, startedAt, error}` — o receptor
  (operador) recebe o nome do job e o motivo, dedup por `eventId`.

### 6. Config (`application.yml`)

```yaml
foxcash:
  scheduler:
    pool-size: 4                       # ${NYX_SCHEDULER_POOL_SIZE:4}
    job-runs:
      retention-days: 30                # ${NYX_JOB_RUNS_RETENTION_DAYS:30}
    job-runs-cleanup-cron: "0 45 4 * * *"
  notification:
    operator-webhook-url: ${NYX_NOTIFICATION_OPERATOR_WEBHOOK_URL:}
    operator-webhook-secret: ${NYX_NOTIFICATION_OPERATOR_WEBHOOK_SECRET:}
```

`SchedulerProperties` (novo) com `poolSize`, `jobRuns.retentionDays`,
`jobRunsCleanupCron`. `NotificationProperties` ganha `operatorWebhookUrl` +
`operatorWebhookSecret` (fail-fast só se uma estiver setada e a outra não).

### 7. Testes

- `JobExecutionServiceTest` (unit, `SimpleMeterRegistry` + mocks): sucesso →
  counter success +1, timer registrado, `job_runs` SUCCESS com `duration_ms`;
  falha → counter failure +1, `job_runs` FAILED com `error`, log ERROR, evento
  `JOB_FAILED` publicado (quando configurado) / não publicado (sem URL).
- `SchedulingConfigTest`: bean `ThreadPoolTaskScheduler` com `pool-size`
  aplicado e prefixo `nyx-job-`.
- Schedulers: testes existentes passam a verificar delegação ao
  `JobExecutionService` (mock) — nenhum job executa lógica fora do runner.
- `NotificationProcessorTest`: evento com `merchantId=null` → entrega ao
  webhook do operador (URL + assinatura do secret do operador); sem URL →
  fail-open.
- E2E (82) sem regressão: `mvn test` verde + smoke manual
  `/actuator/prometheus` com `nyx_job_*` após rodar um job.

## ADR

Nova entrada em `management/adr.md`: **ADR-012 — Execução de jobs: in-process
com métricas, trilha e alerta via outbox** — contexto (pool 1 thread, zero
métricas, falha silenciosa), decisão (pool dedicado + `JobExecutionService` +
`job_runs` + `JOB_FAILED` no outbox para webhook de operador; worker separado
**adiado**), consequências (jobs só rodam com backend de pé; alerta ativo exige
configurar o webhook do operador; `merchant_id` nullable no outbox não afeta
eventos de merchant).

## Arquivos (visão geral)

- `infrastructure/config/SchedulingConfig.java` (bean `ThreadPoolTaskScheduler`)
- `infrastructure/config/SchedulerProperties.java` (novo)
- `infrastructure/config/NotificationProperties.java` (+operador)
- `application/service/JobExecutionService.java` (novo)
- `domain/port/out/JobRunRepository.java` + `JobRunJpaEntity` + adapter (novos)
- `infrastructure/scheduler/*.java` (6 schedulers → delegam ao runner)
- `infrastructure/scheduler/JobRunsCleanupScheduler.java` (novo)
- `domain/model/notification/WebhookEventType.java` (+`JOB_FAILED`)
- `application/service/NotificationProcessor.java` (entrega ao operador)
- `src/main/resources/db/migration/V43__job_runs_and_platform_outbox.sql`
- `src/main/resources/application.yml` (config nova)
- `management/adr.md` (ADR-012)
