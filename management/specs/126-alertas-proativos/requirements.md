---
name: Alertas proativos — observabilidade operacional (métricas de operação + Grafana/regras)
status: audited
references:
  - "management/vision.md#V93"
  - "management/specs/64-alerta-seguranca/requirements.md"
  - "management/specs/53-metricas/requirements.md"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/30-wallet-reconciliation/requirements.md"
  - "management/specs/95-log-drain/requirements.md"
---

# Spec 126 — Alertas proativos: observabilidade operacional

## O que entrega

O sistema JÁ expõe `/actuator/prometheus` (micrometer, Spec 53) e tem 17
métricas `nyx_*` de **PSP/CRM/tracking** — mas **nenhuma métrica de OPERAÇÃO**.
Consequência: "o outbox parou de drenar", "5xx disparou" ou "a reconciliação
achou divergência" só aparecem quando o cliente liga ou alguém olha o painel.

Esta spec fecha o gap em duas camadas:

1. **Métricas novas `nyx_*` de operação** (instrumentação em código, padrão
   micrometer já usado nos routers):
   - `nyx_job_failure_total{job}` + `nyx_job_duration_seconds{job}` — todo job
     (JobExecutionService é o ponto único).
   - `nyx_outbox_delivered_total` / `nyx_outbox_failed_total` — entrega do
     outbox (Spec 35).
   - `nyx_outbox_backlog_age_seconds` — idade do PENDING mais antigo (outbox
     parado = alerta).
   - `nyx_reconciliation_open_issues` — divergências abertas (Spec 30).
   - `nyx_http_5xx_total{route}` — erros 5xx (GlobalExceptionHandler).
   - `nyx_http_429_total{group}` — rate limit estourando (Spec 64).
   - `nyx_webhook_amount_divergence_total` — divergência de valor no PAID
     (B-05, Spec 26).

2. **Configuração de observabilidade + regras + runbook** (documentação
   operacional no repo — `docs/observability/`): scrape do Grafana Cloud,
   regras de alerta YAML (5xx, outbox parado, issues abertas, fallback de
   adquirente, job falhou), dashboard de exemplo e runbook de cada alerta.

## Critérios de aceitação

**C1 — Métricas de job.** Todo job executado via `JobExecutionService.run`
registra `nyx_job_failure_total{job}` (falha) e `nyx_job_duration_seconds{job}`
(duração) — inclusive o caminho de sucesso (counter de falha = 0, timer sempre).

**C2 — Métricas de outbox.** `NotificationProcessor` registra
`nyx_outbox_delivered_total` (SENT) e `nyx_outbox_failed_total` (falhou). O
`NotificationDispatcherService` registra `nyx_outbox_backlog_age_seconds`
(gauge: `now - oldest PENDING.created_at`; 0 quando vazio) a cada run.

**C3 — Métricas HTTP.** `GlobalExceptionHandler` (handler genérico de 5xx)
registra `nyx_http_5xx_total{route}` (path normalizado); `RateLimitFilter` e
`AuthenticatedRateLimitFilter` registram `nyx_http_429_total{group}` no 429.

**C4 — Métricas financeiras.** `WalletReconciliationScheduler` registra
`nyx_reconciliation_open_issues` (gauge após a run); `ProcessPixWebhookService`
registra `nyx_webhook_amount_divergence_total` na divergência B-05.

**C5 — Infra documentada.** `docs/observability/` com: scrape config do
Grafana Cloud (endpoint + auth), regras de alerta (YAML/PromQL) cobrindo as
métricas C1–C4 + os 5xx/Hikari/heap, dashboard JSON de exemplo e runbook
("o que fazer" para cada alerta).

**C6 — Testes.** Cada métrica verificada: job falha → counter; outbox SENT →
delivered; backlog não-vazio → gauge idade; 5xx → counter com rota; 429 →
counter com grupo; divergência → counter. `mvn test` verde.

## Fora de escopo

- Criar a conta/org do Grafana Cloud (config operacional — o repo entrega a
  config e o runbook, não credenciais).
- Alterar o fluxo de dinheiro (nenhuma mudança de domínio financeiro).
- Métricas de negócio adicionais (portal rico, etc.) — fases futuras.

## Dependências

- **Spec 53** (métricas — padrão micrometer `nyx_*` já em uso).
- **Spec 35** (outbox — NotificationProcessor/Dispatcher).
- **Spec 30** (reconciliação — issues).
- **Spec 64** (alerta de segurança — 429/grupo).
- **Spec 95** (log drain — contexto de observabilidade existente).
