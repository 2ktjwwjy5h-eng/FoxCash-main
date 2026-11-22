# Spec 53 — Task breakdown

Status: `audited` (2026-08-07, `/spec-audit`). `mvn test` **1075/0/0** · E2E
**84/84** · JaCoCo OK. Feature LIVE.

## Implementação

- [x] Migration **V43__job_runs_and_platform_outbox.sql**:
      - `CREATE TABLE job_runs` (id BIGSERIAL PK, job VARCHAR(64), started_at,
        finished_at, duration_ms BIGINT, status CHECK IN ('SUCCESS','FAILED'),
        error TEXT) + índice `(job, started_at DESC)`.
      - `ALTER TABLE notification_outbox ALTER COLUMN merchant_id DROP NOT NULL`
        (eventos de plataforma; FK mantida).
- [x] `SchedulerProperties` (prefixo `foxcash.scheduler`): `poolSize` (default
      4), `jobRuns.retentionDays` (default 30), `jobRunsCleanupCron` (default
      `0 45 4 * * *`).
- [x] `SchedulingConfig`: bean `ThreadPoolTaskScheduler` (pool-size +
      `threadNamePrefix="nyx-job-"` +
      `waitForTasksToCompleteOnShutdown=true` + await 30s).
- [x] `JobRunRepository` (port/out) + `JobRunJpaEntity` + adapter JPA (padrão
      dos repositórios existentes; sem mapper — write-only).
- [x] `JobExecutionService.run(jobName, Runnable)`:
      - cronometra — Micrometer `nyx.job.duration` (tag `job`;
        → `nyx_job_duration_seconds` no Prometheus),
      - counters `nyx.job.success` / `nyx.job.failure` (tag `job`; →
        `nyx_job_success_total` / `nyx_job_failure_total` no Prometheus — SEM
        sufixo `_total` no nome Micrometer),
      - grava `job_runs` (SUCCESS/FAILED, `duration_ms`, `error` truncado 500),
      - log estruturado INFO/ERROR com `job=`/`status=`/`duration_ms=`/`error=`,
      - em falha: publica `JOB_FAILED` no outbox **se** webhook do operador
        configurado; NÃO re-propaga (falha da própria infra → WARN).
- [x] `WebhookEventType.JOB_FAILED` + `WebhookEventPublisher.publish` aceitando
      `merchantId == null` (payload com `merchantId: null`).
- [x] `NotificationProperties`: `operatorWebhookUrl` +
      `operatorWebhookSecret` (fail-fast se só uma setada, no `validate()`);
      **a entrega ao operador ficou no `HttpMerchantNotifier.deliver`** (evento
      com `merchantId == null` → webhook do operador com HMAC do secret do
      operador; sem URL → fallback log) — o `NotificationProcessor` não mudou
      (continua orquestrando `notifier.deliver`).
- [x] Refatorar os **6 schedulers** para delegar ao `JobExecutionService`
      (remover lógica/`try`/`log` individual): InvoiceGeneration,
      InvoiceCollection, RiskMonitoring, WalletReconciliation,
      NotificationDispatch, WithdrawalReconciliation + novo
      `JobRunsCleanupScheduler` (diário, ShedLock `foxcash-job-runs-cleanup`,
      também delegando ao runner — falha do cleanup entra na métrica/trilha).
- [x] `application.yml`: `foxcash.scheduler.pool-size` / `job-runs.*` /
      `job-runs-cleanup-cron` + `foxcash.notification.operator-webhook-*`.
- [x] Testes: `JobExecutionServiceTest` (5 — sucesso/falha/outbox on-off/infra
      falha/mensagem vazia), `SchedulingConfigTest` (2 — pool-size/prefixo),
      `NotificationPropertiesTest` (+3 — fail-fast operador), 
      `HttpMerchantNotifierTest` (+2 — evento de plataforma → operador com
      assinatura; sem URL → fail-open).
- [x] ADR-012 em `management/adr.md` (execução in-process + métricas + alerta
      via outbox; worker separado adiado).

## Verificação

- [x] `mvn test` **1075/0/0** (1060 → 1075: +15 novos).
- [x] E2E 84/84 sem regressão (auth 24 + journey 27 + security 31 + psp-flow 1
      + reliability 1) — rodado na auditoria contra a app real (2.6m).
- [x] Smoke `/actuator/prometheus` ao vivo: `nyx_job_duration_seconds_count/
      sum/max` + `nyx_job_success_total{job="foxcash-notification-dispatch"}`
      (sem `_total_total`) após o job de notificação rodar (30s).
- [x] Smoke `job_runs` ao vivo: 2 linhas SUCCESS com `duration_ms` (V43
      aplicada no boot, Hibernate validate OK).
- [x] Smoke threads: pool ativo — log estruturado em thread **`nyx-job-1`**
      (`job=foxcash-notification-dispatch status=SUCCESS duration_ms=24`).
- [x] Smoke falha: coberto por **unit** (`JobExecutionServiceTest` —
      contador/trilha/outbox on-off + `HttpMerchantNotifierTest` — entrega ao
      operador com MockWebServer); smoke ao vivo com
      `foxcash.psp.mock-payout-failure` requer disparar o cron de 04:30 UTC —
      adiado (cobertura equivalente em unit).

## Auditoria (ciclo SDD — `/spec-audit`, 2026-08-07)

**7/7 critérios OK** — `mvn test` **1075/0/0**, E2E **84/84**, `mvn verify`
(JaCoCo) "All coverage checks have been met".

- **Critério 1 (pool):** `SchedulingConfigTest` (pool-size/prefixo) + smoke ao
  vivo (thread `nyx-job-1`). O caso "cron longo NÃO atrasa o outbox" é
  garantido por construção (pool ≥ 2 threads do `ThreadPoolTaskScheduler`) —
  sem teste de integração Spring (pesado p/ o valor); aceito por design.
- **Critério 2 (métricas):** smoke `/actuator/prometheus` ao vivo —
  `nyx_job_duration_seconds_{count,sum,max}` + `nyx_job_success_total` (sem
  `_total_total`); `nyx_job_failure_total` coberto por unit
  (`JobExecutionServiceTest` — exigiria um job falhando ao vivo).
- **Critério 3 (falha):** `JobExecutionServiceTest` — log ERROR estruturado,
  counter failure +1, `job_runs` FAILED com erro, `JOB_FAILED` no outbox
  (quando operador configurado).
- **Critério 4 (job_runs + limpeza):** smoke ao vivo (`job_runs` gravando com
  `duration_ms`) + `JobRunPersistenceAdapterTest` (save mapeia SUCCESS/FAILED
  e `deleteOlderThan` delega ao repo) — novo na auditoria.
- **Critério 5 (operador):** `HttpMerchantNotifierTest` — evento de plataforma
  entregue ao webhook do operador com assinatura HMAC do secret do operador;
  sem URL → fail-open (não publica). Retry/backoff pelo pipeline da Spec 35.
- **Critério 6 (merchant_id nullable):** migration V43 + entity nullable +
  Hibernate validate OK no boot.
- **Critério 7 (suíte):** `mvn test` **1075/0/0** + E2E **84/84** (auth 24 +
  journey 27 + security 31 + psp-flow 1 + reliability 1) sem regressão.

**Achados corrigidos na auditoria:**
- **Docs (requirements + design):** o header de assinatura do webhook de
  operador estava documentado como `X-Psp-Signature` — **errado**: esse é o
  header do webhook de ENTRADA do PSP. O contrato real (Spec 35, implementado)
  usa `X-FoxCash-Signature`. Corrigido nos dois documentos.
- **Cobertura do cleanup:** adicionado `JobRunPersistenceAdapterTest` (3
  testes) para fechar o critério 4 com teste de delegação.

**Aceitos por design:**
- Teste de integração Spring "2 jobs em paralelo" não adicionado (o pool de
  threads garante por construção; smoke ao vivo confirma o pool ativo).
- Smoke de falha ao vivo com `foxcash.psp.mock-payout-failure` não executado
  (job é cron 04:30 UTC); o caminho de falha é coberto por unit.

**Fim do ciclo SDD:** roadmap `audited` (feature LIVE).
