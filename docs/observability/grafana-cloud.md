# Grafana Cloud — scrape de métricas (Spec 126)

> Documento de infraestrutura. **Nenhuma credencial vai para este arquivo.** Os
> valores reais (URLs, tokens, org/stack) vivem em secrets do Railway/CI.

## 1. O que já existe no app

O serviço expõe o endpoint Prometheus do actuator em
`/actuator/prometheus` (Spring Boot + `micrometer-registry-prometheus`):

- `management.endpoints.web.include: health,info,prometheus,metrics`
- `management.endpoints.web.base-path: /actuator`
- O endpoint exige `ROLE_ADMIN` (ver `SecurityConfig`) — **proteger o scrape
  com Basic Auth** se o endpoint ficar público no Railway.

Métricas `foxcash_*` (nomes no formato Prometheus — Micrometer converte `.` em `_`):

| Métrica | Tipo | Tags | Origem |
|---|---|---|---|
| `foxcash_job_duration_seconds` | Timer | `job` | `JobExecutionService` (Spec 53) |
| `foxcash_job_success_total` | Counter | `job` | `JobExecutionService` |
| `foxcash_job_failure_total` | Counter | `job` | `JobExecutionService` |
| `foxcash_outbox_delivered_total` | Counter | — | `NotificationProcessor` |
| `foxcash_outbox_failed_total` | Counter | — | `NotificationProcessor` |
| `foxcash_outbox_backlog_age_seconds` | Gauge | — | `NotificationDispatcherService` |
| `foxcash_outbox_published_total` | Counter | — | `OutboxRelay` (Spec 134) |
| `foxcash_outbox_consumed_total` | Counter | — | `OutboxConsumer` (Spec 134) |
| `foxcash_outbox_dlq_total` | Counter | — | `OutboxConsumer` (Spec 134) |
| `foxcash_reconciliation_open_issues` | Gauge | — | `WalletReconciliationScheduler` |
| `foxcash_http_5xx_total` | Counter | `route` | `GlobalExceptionHandler` |
| `foxcash_http_429_total` | Counter | `group` | `RateLimitFilter`/`AuthenticatedRateLimitFilter` |
| `foxcash_webhook_amount_divergence_total` | Counter | — | `ProcessPixWebhookService` (B-05) |
| `foxcash_psp_route_total` | Counter | `provider`, `policy`, `outcome` | `PixProviderRouter` (Spec 91) |
| `foxcash_psp_charge_created_total` | Counter | `provider`, `policy`, `outcome` | `PixProviderRouter` |

Junto com as métricas padrão do Spring Boot: `hikaricp_connections_*` (pool),
`jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes`, `http_server_requests_seconds`.

## 2. Como apontar o Grafana Cloud para o Railway

O Grafana Cloud pode **scrapear endpoints HTTPS públicos** diretamente (hosted
Prometheus) — o Railway expõe `https://<app>.up.railway.app`.

### Passo a passo

1. **Stack/Grafana Cloud**: crie/use um stack Prometheus (Metrics) no Grafana
   Cloud. Anote `prometheus-us-central1.grafana.net` (ou a região do stack) e o
   **instance ID** — ambos via *Details* do stack.
2. **Scrape job**: em *Hosted Prometheus → Scrape Config* (ou via `alloy`
   local, se preferir um coletor próprio), adicione o job:

   ```yaml
   scrape_configs:
     - job_name: foxcash-railway
       scheme: https
       metrics_path: /actuator/prometheus
       static_configs:
         - targets: ["<app>.up.railway.app"]
       # Se o endpoint exigir auth (ROLE_ADMIN + Basic Auth no SecurityConfig):
       basic_auth:
         username: ${ACTUATOR_ADMIN_USER}
         password: ${ACTUATOR_ADMIN_PASSWORD}
       # Rotula o ambiente (dev/prod) — útil para multi-instância:
       metric_relabel_configs:
         - source_labels: [__address__]
           target_label: instance
   ```

3. **Intervalo e limites** (ver seção 3).
4. **Dashboards**: importe `docs/observability/dashboard-example.json`
   (painel "Operação").
5. **Regras**: aplique `docs/observability/alert-rules.yml` (Grafana Managed
   Alerts ou regras do hosted Prometheus) e conecte o canal de notificação
   (Slack/e-mail/PagerDuty).

### Alternativa: Grafana Alloy/Agent no Railway

Se não quiser scrape público, rode um `alloy` (Grafana Agent) como serviço
adicional no Railway, com `prometheus.scrape` para o mesmo endpoint e
`prometheus.remote_write` para o hosted Prometheus do Grafana Cloud. As variáveis
(`GRAFANA_CLOUD_INSTANCE_ID`, `GRAFANA_CLOUD_TOKEN`) ficam nos secrets do Railway.

## 3. Limites (cardinalidade / custo)

- **Intervalo de scrape**: 15s (default do hosted Prometheus) — suficiente para
  alertas de 5–15 min.
- **Cardinalidade das tags**:
  - `foxcash_http_5xx_total{route}` — **normalizar** a rota (IDs substituídos por
    `{id}`). Se a rota chegar crua com UUID, cada transação cria série nova →
    estoura a cardinalidade. (A métrica usa `StringUtils.cleanPath`; garanta que
    o router use templates, ex. `/api/v1/transactions/{id}`.)
  - `foxcash_psp_route_total{provider,policy,outcome}` — cardinalidade baixa
    (poucos provedores × poucas políticas).
- **Retenção**: hosted Prometheus do Grafana Cloud tem retenção própria
  (geralmente 13 meses no plano Pro; o plano Free tem retenção menor). Não
  precisa de storage extra.

## 4. Segurança

- O `/actuator/prometheus` pode expor detalhes internos (nomes de beans,
  endpoints). **Não exponha publicamente sem auth** — o mesmo `ROLE_ADMIN` que
  protege os demais endpoints do actuator vale aqui.
- Nenhuma credencial em código/config versionado. Use secrets do Railway
  (`ACTUATOR_ADMIN_USER`/`ACTUATOR_ADMIN_PASSWORD`) e variáveis de ambiente do
  Grafana Cloud.
