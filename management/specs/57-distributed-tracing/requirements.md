---
name: Tracing distribuído (Micrometer Tracing + OTLP)
status: audited
references:
  - "management/vision.md#V37"
  - "management/specs/53-job-robustez/requirements.md"
---

# Spec 57 — Tracing distribuído

## O que entrega

Adiciona **tracing distribuído** ao backend via Micrometer Tracing (bridge
OpenTelemetry) + exporter OTLP: spans de requisições HTTP (e instrumentação
automática de JDBC/Redis onde aplicável) com `traceId`/`spanId` propagados nos
logs estruturados (Spec 53/observabilidade) e exportados a um collector OTLP
(Tempo/Grafana/Jaeger). Verificado na auditoria de produção 2026-08-07: só
métricas Prometheus existiam — nenhum tracing.

## Critérios de aceite

1. `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` no
   classpath; tracing **habilitado por default** (`management.tracing.enabled=true`).
2. Sampling configurável via `NYX_TRACING_SAMPLING_PROBABILITY` (default `0.1`
   = 10%).
3. Endpoint do collector via `NYX_OTLP_ENDPOINT` (sem default em produção —
   **sem collector configurado a app NÃO derruba nem bloqueia**: spans são
   exportados em background, falha de export é logada/descartada sem afetar as
   requisições).
4. `traceId`/`spanId` presentes nos logs estruturados (formato do
   `logback-spring.xml` — campo `traceId`/`spanId` quando o tracing está ativo,
   ausente quando não).
5. `mvn test` sem regressão; E2E sem regressão (o tracing não altera contrato
   de API).

## Decisões de design

- **Dependências:** `io.micrometer:micrometer-tracing-bridge-otel` +
  `io.opentelemetry:opentelemetry-exporter-otlp` (exporter OTLP gRPC ou HTTP —
  preferir **HTTP** `opentelemetry-exporter-otlp` com endpoint
  `http://<collector>:4318/v1/traces`, simples no Railway e compatível com
  Grafana Tempo/Jaeger/Grafana Cloud).
- **Sem collector:** o `OtlpSpanExporter` falha silenciosamente em background
  (retry/backoff do SDK OTel); a app segue servindo 100% normalmente. Nada de
  fail-fast — tracing é observabilidade, não dependência de disponibilidade.
- **Sampling:** `management.tracing.sampling.probability` (default 0.1). Em
  produção Railway, se o usuário não configurar `NYX_OTLP_ENDPOINT`, o tracing
  fica habilitado mas sem destino (spans descartados após retries) — o
  `traceId` nos logs ainda funciona (contexto local).
- **Logs:** o `logback-spring.xml` do prod ganha `%X{traceId} %X{spanId}` (MDC
  do Micrometer Tracing) — ausentes (vazios) quando não há trace ativo.

## Arquivos

| Arquivo | Ação |
|---|---|
| `pom.xml` | adicionar dependências de tracing |
| `application.yml` | bloco `management.tracing` + `management.otlp.tracing` |
| `src/main/resources/logback-spring.xml` | adicionar `traceId`/`spanId` ao pattern do prod |
| README/deploy runbook | documentar `NYX_OTLP_ENDPOINT`/`NYX_TRACING_SAMPLING_PROBABILITY` |
