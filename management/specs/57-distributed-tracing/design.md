# Spec 57 — Design: tracing distribuído

## Stack

| Componente | Artefato |
|---|---|
| Bridge Micrometer → OTel | `io.micrometer:micrometer-tracing-bridge-otel` |
| Exporter OTLP (HTTP) | `io.opentelemetry:opentelemetry-exporter-otlp` |

O Micrometer Tracing (parte do Spring Boot 3) instrumenta automaticamente:
requisições HTTP (servlet filter `ObservationFilter`/`ServerHttpObservationFilter`),
JDBC (via `micrometer-observation` + datasource proxy — coberto pelo starter),
Redis (se o cliente Observation estiver no classpath) e o `JobExecutionService`
da Spec 53 (observação já existente — ganha spans sem mudança). O `logback`
consome o MDC (`traceId`/`spanId`) preenchido pela bridge.

## Configuração (`application.yml`)

```yaml
management:
  tracing:
    # Spec 57: tracing distribuído — habilita spans HTTP/JDBC com Micrometer.
    # Sem collector configurado a app NÃO derruba (exporter falha em background).
    enabled: true
    sampling:
      probability: ${NYX_TRACING_SAMPLING_PROBABILITY:0.1}
  otlp:
    tracing:
      endpoint: ${NYX_OTLP_ENDPOINT:}
```

## `logback-spring.xml` (prod)

Pattern do prod (atual: `ts level logger requestId msg`) ganha:

```xml
<pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} level=%level logger=%logger{36} requestId=%X{requestId} traceId=%X{traceId} spanId=%X{spanId} msg=%msg%n</pattern>
```

Sem trace ativo → `traceId=`/`spanId=` vazios (comportamento atual preservado;
a suíte E2E de logs — se existir — não quebra).

## Sem collector (fail-open)

O `OtlpHttpSpanExporter` **rejeita endpoint vazio no boot** (`Invalid endpoint,
must start with http:// or https://` — validação do SDK OTel; descoberto na
implementação). Para garantir fail-open, o `application.yml` usa o default
`http://localhost:4318/v1/traces` (`${NYX_OTLP_ENDPOINT:http://localhost:4318/v1/traces}`):
sem collector configurado, o exporter tenta o endpoint local em background com
retry/backoff do SDK e **nunca derruba nem bloqueia requisições** (falha
assíncrona descartada). O `traceId`/`spanId` continuam preenchidos no MDC
(mesmo sem export). Configurar `NYX_OTLP_ENDPOINT` para um collector real
(Tempo/Grafana/Jaeger); para desligar 100%: `NYX_TRACING_ENABLED=false` ou
`management.tracing.enabled=false` via env.

## Testes

- Teste de contexto: boot com tracing habilitado → bean `Tracer`/
  `ObservationRegistry` presente; `management.tracing.enabled` false → sem
  bean (se aplicável).
- Smoke: request real no app dev → log do prod/request com `traceId` não-vazio
  (via MDC) e `/actuator/metrics` expondo `http.server.requests` com tag
  `traceId` (opcional).
- Sem regressão em `mvn test` + E2E (contrato de API inalterado).

## Arquivos

| Arquivo | Ação |
|---|---|
| `pom.xml` | +2 dependências (micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp) |
| `src/main/resources/application.yml` | bloco `management.tracing` + `management.otlp.tracing` |
| `src/main/resources/logback-spring.xml` | `traceId`/`spanId` no pattern do prod |
| `README.md`/`deploy/railway.md` | env vars `NYX_OTLP_ENDPOINT`/`NYX_TRACING_SAMPLING_PROBABILITY` |
