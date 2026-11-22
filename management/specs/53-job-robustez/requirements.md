---
name: Robustez da execução de jobs — threads, métricas, trilha e alertas
status: audited
references:
  - "management/vision.md#V33"
  - "management/specs/6-invoice-scheduler/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/adr.md#ADR-012"
---

# Spec 53 — Robustez da execução de jobs

## O que entrega

Infraestrutura única de execução para os **6 jobs do backend** (faturas,
cobrança/receivables, risco, reconciliação do wallet, dispatcher de
outbox/notificações — um único scheduler cobre os dois — e reconciliação de
saques), que hoje rodam in-process com `@Scheduled` + ShedLock (Spec 6) mas sem
controle operacional. Entregáveis:

1. **Pool de threads dedicado** — bean `ThreadPoolTaskScheduler` com pool
   configurável (`foxcash.scheduler.pool-size`, default 4, prefixo
   `nyx-job-`). O outbox (poll 30s) deixa de ficar bloqueado por cron longo e
   vice-versa — hoje o pool default do Spring é 1 thread.

2. **Métricas Micrometer por job** — cada execução cronometrada e contada,
   expostas no Prometheus como `nyx_job_duration_seconds` (timer/histograma),
   `nyx_job_success_total` e `nyx_job_failure_total` (counters), todas com tag
   `job`. **Naming Micrometer:** os counters são registrados como
   `nyx.job.success` / `nyx.job.failure` (SEM sufixo `_total` — o Prometheus o
   anexa; nomear com `_total` geraria `_total_total`). `/actuator/prometheus`
   já existe e exige `ROLE_ADMIN` (observabilidade 2026-08-07). **Hoje o
   Micrometer está no classpath mas nenhum job o usa.**

3. **Trilha auditável `job_runs`** — tabela nova (migration V43): `job`,
   `started_at`, `finished_at`, `duration_ms`, `status` (`SUCCESS`/`FAILED`),
   `error` — registro de TODA execução (sucesso e falha). Retenção
   configurável (`foxcash.scheduler.job-runs.retention-days`, default 30) com
   job de limpeza diário.

4. **Alerta de falha via outbox** — evento **`JOB_FAILED`** (plataforma, sem
   merchant) publicado no `notification_outbox` quando um job falha e entregue
   a um **webhook de operador** (`foxcash.notification.operator-webhook-url` +
   `operator-webhook-secret`), reusando TODO o pipeline da Spec 35 (dispatcher
   30s, retry/backoff exponencial, assinatura HMAC-SHA256, dedup por
   `eventId`). Exige tornar `notification_outbox.merchant_id` nullable (evento
   de plataforma) + `WebhookEventType.JOB_FAILED`.

5. **Abstração única de execução** — `JobExecutionService.run(jobName,
   Runnable)`: cronometra, registra métrica, grava `job_runs` e captura falha
   (log estruturado `level=ERROR job= status=FAILED` + contador + linha em
   `job_runs` + evento `JOB_FAILED` no outbox). Os 6 schedulers delegam a ela
   (remoção de try/catch individual — hoje nenhum trata falha).

**Fora de escopo:** worker separado (spec futura — depende de escala real; o
outbox já é o "fio" para separar sem tocar no domínio) e fila externa
(RabbitMQ/SQS).

## Critérios de aceite

- `foxcash.scheduler.pool-size` (default 4) aplicado: threads com prefixo
  `nyx-job-`; um cron longo NÃO atrasa o poll do outbox (teste de integração
  com 2 jobs: um `fixedDelay` curto e um job que dorme > intervalo).
- `/actuator/prometheus` expõe `nyx_job_duration_seconds`,
  `nyx_job_success_total` e `nyx_job_failure_total` com tag `job` para cada um
  dos 6 jobs após uma execução (validação manual + teste unitário do
  `JobExecutionService` com `SimpleMeterRegistry`).
- Falha de job (ex.: PSP indisponível na reconciliação de saques) → **1**
  log estruturado ERROR com `job=`/`status=FAILED`, contador de falha +1,
  linha em `job_runs` com `status=FAILED` e `error` preenchido, e evento
  `JOB_FAILED` no outbox (payload com `job`, `error`, `startedAt`).
- `job_runs`: toda execução grava 1 linha (SUCCESS ou FAILED) com
  `duration_ms` > 0; job de limpeza diário apaga registros com
  `started_at < now() - retention-days` (default 30); retenção configurável.
- Webhook de operador: com `operator-webhook-url` configurado, `JOB_FAILED` é
  entregue com assinatura `X-FoxCash-Signature` (HMAC do
  `operator-webhook-secret` — mesmo header do webhook de saída, Spec 35) e
  retry/backoff (Spec 35); sem URL configurada, o evento não é criado (o
  alerta é só métrica + log + `job_runs` — fail-open operacional).
- `notification_outbox.merchant_id` nullable: eventos de plataforma
  (`JOB_FAILED`) persistem com `NULL`; eventos de merchant continuam com FK
  (sem regressão na Spec 35).
- `mvn test` verde (novos: `JobExecutionServiceTest`, `SchedulingConfigTest`,
  testes dos schedulers delegando ao runner) e E2E sem regressão (82 testes).

## Dependências

- `management/specs/6-invoice-scheduler/requirements.md` (ShedLock — base da
  execução única)
- `management/specs/35-outbound-webhook/requirements.md` (pipeline do outbox —
  dispatcher, retry/backoff, assinatura; o alerta reusa)
- `management/adr.md` (ADR-012 — modelo de execução de jobs: in-process +
  métricas + alerta via outbox; worker separado adiado)
- Campanha de observabilidade 2026-08-07 (actuator/prometheus/log estruturado —
  base do entregável 2)
- `management/vision.md#V33`
