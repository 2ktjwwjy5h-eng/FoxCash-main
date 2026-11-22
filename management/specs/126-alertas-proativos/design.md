---
name: Alertas proativos — observabilidade operacional (métricas de operação + Grafana/regras)
status: created
---

# Spec 126 — Design: Alertas proativos

## D1 — Conceito

A infra de métricas JÁ existe (actuator + prometheus + micrometer). O gap é
**quais métricas** existem: 17 `nyx_*` de PSP/CRM/tracking, zero de operação.
Esta spec instrumenta os **pontos de falha que importam para o dinheiro** e
entrega a **configuração de alerta** (Grafana Cloud) + runbook.

Decisão de arquitetura: **nenhuma mudança de domínio financeiro** — as métricas
são instrumentação observacional (MeterRegistry), padrão já usado nos routers
(`Counter.builder(name).tag(...)`).

## D2 — Métricas novas (tabela de instrumentação)

| Métrica | Tipo | Onde | Tags | Alerta |
|---|---|---|---|---|
| `nyx_job_failure_total` | Counter | `JobExecutionService.run` (catch) | `job` | `> 0` em janela |
| `nyx_job_duration_seconds` | Timer | `JobExecutionService.run` | `job` | p95 alto |
| `nyx_outbox_delivered_total` | Counter | `NotificationProcessor.process` (SENT) | — | — |
| `nyx_outbox_failed_total` | Counter | `NotificationProcessor.process` (falhou/esgotou) | — | rate de falha |
| `nyx_outbox_backlog_age_seconds` | Gauge | `NotificationDispatcherService` (a cada run) | — | `> 5 min` |
| `nyx_reconciliation_open_issues` | Gauge | `WalletReconciliationScheduler` (após run) | — | `> 0` |
| `nyx_http_5xx_total` | Counter | `GlobalExceptionHandler` (handler genérico) | `route` | rate > 0 |
| `nyx_http_429_total` | Counter | `RateLimitFilter`/`AuthenticatedRateLimitFilter` (429) | `group` | rate alto |
| `nyx_webhook_amount_divergence_total` | Counter | `ProcessPixWebhookService.amountDiverges` | — | `> 0` |

## D3 — Instrumentação

Padrão: injetar `MeterRegistry` nos componentes e usar o mesmo helper dos
routers (`Counter.builder(name).tag("k", v).register(registry)`).

- **JobExecutionService** (T1): JÁ EXISTEM (Spec 53) — `nyx.job.duration`
  (Timer) + `nyx.job.failure`/`nyx.job.success` (Counters) com tag `job`;
  T1 foi VERIFICAÇÃO (Prometheus: `nyx_job_duration_seconds{job}`,
  `nyx_job_failure_total{job}`). Testes já existem no `JobExecutionServiceTest`.
- **Outbox** (T2): `NotificationProcessor` — counter delivered/failed nos dois
  retornos (`return true`/`false` e no esgotamento). `NotificationOutboxPort` +
  adapter + JPA ganham `Optional<Instant> findOldestPendingCreatedAt()`
  (query `MIN(created_at) WHERE status='PENDING'`); o `NotificationDispatcherService`
  registra o gauge `now - oldest` (0 se vazio) a cada `dispatchPending`.
- **HTTP** (T3): `GlobalExceptionHandler` — o handler genérico (500) registra
  `nyx_http_5xx_total{route}` com `request.getRequestURI()` normalizado.
  `RateLimitFilter`/`AuthenticatedRateLimitFilter` — `nyx_http_429_total{group}`
  no bloco do 429 (o `group`/`"authenticated"` já é calculado).
- **Financeiro** (T4): `WalletReconciliationScheduler` — após a run, gauge
  `nyx_reconciliation_open_issues` = `reconciliationIssueRepository.find(null,
  OPEN, 0, 1).totalElements()`. `ProcessPixWebhookService.amountDiverges` —
  counter no `log.warn` existente (B-05).

## D4 — Infra documentada (`docs/observability/`)

```
docs/observability/
├── grafana-cloud.md      # scrape config (endpoint /actuator/prometheus + auth),
│                         #   org/stack, limites — SEM credenciais
├── alert-rules.yml       # regras PromQL por métrica (abaixo)
├── dashboard-example.json # painel "Operação" (outbox, jobs, 5xx, 429, PSP)
└── runbook.md            # "o que fazer" para cada alerta
```

**Regras de alerta (exemplo, em `alert-rules.yml`):**

```yaml
# outbox parado: nenhum PENDING entregue há 5 min
- alert: OutboxStalled
  expr: nyx_outbox_backlog_age_seconds > 300
# divergência de saldo na reconciliação
- alert: ReconciliationDivergence
  expr: nyx_reconciliation_open_issues > 0
# adquirente caindo (fallback disparando) — tag real do código: `outcome`
- alert: PspFallbackSpike
  expr: increase(nyx_psp_route_total{outcome="fallback"}[15m]) > 10
# job falhou
- alert: JobFailed
  expr: increase(nyx_job_failure_total[15m]) > 0
# 5xx (rota) subindo
- alert: Http5xxSpike
  expr: rate(nyx_http_5xx_total[15m]) > 0.5
```

**Runbook (resumo em `runbook.md`):** para cada alerta → ver o trace da transação
(Spec 125), drenar o outbox, verificar o adquirente no `PixProviderRouter`,
consultar a trilha (Spec 41), re-rodar a reconciliação.

## D5 — Arquivos previstos

- Modificados: `JobExecutionService`, `NotificationProcessor`,
  `NotificationDispatcherService`, `NotificationOutboxPort` (+adapter+JPA),
  `GlobalExceptionHandler`, `RateLimitFilter`, `AuthenticatedRateLimitFilter`,
  `WalletReconciliationScheduler`, `ProcessPixWebhookService`
- Novos: `docs/observability/{grafana-cloud.md, alert-rules.yml,
  dashboard-example.json, runbook.md}`
- Testes: `ObservabilityMetricsTest` (ou verifies nos testes existentes dos
  componentes tocados) — cada métrica registrada nos fluxos C1–C4.
