# Spec 57 — Task: tracing distribuído

- [ ] 1. Adicionar ao `pom.xml`: `io.micrometer:micrometer-tracing-bridge-otel`
      e `io.opentelemetry:opentelemetry-exporter-otlp`.
- [ ] 2. Adicionar bloco `management.tracing` (`enabled: true`, sampling
      `NYX_TRACING_SAMPLING_PROBABILITY:0.1`) e `management.otlp.tracing.endpoint`
      (`NYX_OTLP_ENDPOINT:`) ao `application.yml`.
- [ ] 3. Adicionar `traceId=%X{traceId} spanId=%X{spanId}` ao pattern do prod
      no `logback-spring.xml`.
- [ ] 4. `mvn test` verde (sem regressão) — verificar se o tracing afeta
      testes de contexto (beans novos).
- [ ] 5. Smoke dev: request → log com `traceId`; sem `NYX_OTLP_ENDPOINT` a app
      segue saudável (health 200).
- [ ] 6. Deploy em Railway: sem `NYX_OTLP_ENDPOINT` a app sobe normalmente e
      logs carregam `traceId`; documentar `NYX_OTLP_ENDPOINT`/
      `NYX_TRACING_SAMPLING_PROBABILITY` no runbook.
