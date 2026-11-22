---
name: Alertas proativos — observabilidade operacional (métricas de operação + Grafana/regras)
status: audited
---

# Spec 126 — Task: Alertas proativos

Ordem: T0 (reconhecimento) → T1 (jobs) → T2 (outbox) → T3 (HTTP) → T4
(financeiro) → T5 (docs infra) → T6 (testes/auditoria). Nenhuma mudança de
domínio financeiro — instrumentação observacional apenas.

## T0 — Reconhecimento (critérios C1–C4)

- [x] T0.1: Confirmar `/actuator/prometheus` exposto (já configurado) e o padrão
      `meter()` dos routers (MeterRegistry injetado + `Counter.builder`).
- [x] T0.2: Confirmar os pontos: `JobExecutionService.run` (durationMs/status),
      `NotificationProcessor.process` (retornos), `GlobalExceptionHandler`
      (handler genérico), `RateLimitFilter`/`AuthenticatedRateLimitFilter` (429).

## T1 — Métricas de job (C1)

- [x] T1.1: JÁ EXISTE no `JobExecutionService` (Spec 53): `nyx.job.duration`
      (Timer) + `nyx.job.failure`/`nyx.job.success` (Counters) com tag `job` —
      verificado em código (Prometheus: `nyx_job_duration_seconds`, `nyx_job_failure_total`).
- [x] T1.2: Coberto pelos testes existentes do `JobExecutionService` (verificação
      — nenhuma métrica nova necessária).

## T2 — Métricas de outbox (C2)

- [x] T2.1: `NotificationOutboxPort` += `Optional<Instant> findOldestPendingCreatedAt()`
      (+ adapter JPA `@Query("SELECT MIN(e.createdAt) ... WHERE e.status = 'PENDING'")`
      + `NotificationOutboxJpaRepository`).
- [x] T2.2: `NotificationProcessor` — counters `nyx_outbox_delivered_total`
      (SENT) e `nyx_outbox_failed_total` (falha/esgotou).
- [x] T2.3: `NotificationDispatcherService` — gauge `nyx_outbox_backlog_age_seconds`
      (avaliado no scrape; 0 se vazio).
- [x] T2.4: Testes — SENT → delivered; falha → failed; backlog não-vazio →
      gauge = idade (NotificationProcessorTest + NotificationDispatcherServiceTest).

## T3 — Métricas HTTP (C3)

- [x] T3.1: `GlobalExceptionHandler` — handler genérico (500) registra
      `nyx_http_5xx_total{route}` (path normalizado do request).
- [x] T3.2: `RateLimitFilter` + `AuthenticatedRateLimitFilter` — `nyx_http_429_total{group}`
      no bloco do 429 (grupo já calculado).
- [x] T3.3: Testes — 500 → counter com rota; 429 → counter com grupo.

## T4 — Métricas financeiras (C4)

- [x] T4.1: `WalletReconciliationScheduler` — gauge `nyx_reconciliation_open_issues`
      (avaliado no scrape via `findIssues(null, OPEN, 0, 1).totalElements()`).
- [x] T4.2: `ProcessPixWebhookService.amountDiverges` — counter
      `nyx_webhook_amount_divergence_total` (B-05).
- [x] T4.3: Testes — divergência → counter; scheduler → gauge.

## T5 — Documentação de infra (C5)

- [x] T5.1: `docs/observability/grafana-cloud.md` — scrape config do
      `/actuator/prometheus` + auth, limites (SEM credenciais).
- [x] T5.2: `docs/observability/alert-rules.yml` — regras PromQL (outbox
      parado, divergência, fallback PSP, job falhou, 5xx, 429).
- [x] T5.3: `docs/observability/dashboard-example.json` — painel "Operação".
- [x] T5.4: `docs/observability/runbook.md` — o que fazer para cada alerta
      (drenar outbox, ver trace Spec 125, trocar adquirente, re-rodar
      reconciliação).

## T6 — Verificação (C6)

- [x] T6.1: `mvn clean test` completo verde — **1967/1967** (0 falhas, 0 erros).
      Corrigido `import java.util.Optional` faltante no
      `NotificationOutboxJpaRepository` (causa raiz dos 4 erros do Mockito em
      `NotificationOutboxAdapterTest` — classe `.class` stale do build
      incremental; build limpo + import resolvem).
- [x] T6.2: Verificação + auditoria concluídas — C1–C6 verificados critério a
      critério. Achado único: gauge do `WalletReconciliationScheduler` sem teste →
      criado `WalletReconciliationSchedulerTest` (2 testes). design.md corrigido
      (tag do PSP é `outcome`, não `result`; T1 = verificação). Status final:
      `audited` — Spec DONE, feature LIVE.
