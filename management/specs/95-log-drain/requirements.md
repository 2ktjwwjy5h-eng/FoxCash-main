---
name: Log drain e agregação de logs
status: audited
references:
  - "management/vision.md#V71"
  - "management/specs/64-sec-observability-incident-response/requirements.md"
  - "management/specs/41-master-profiles-activity/requirements.md"
  - "management/specs/53-job-robustez/requirements.md"
  - "management/specs/57-distributed-tracing/requirements.md"
---

# Spec 95 — Log drain e agregação de logs (lacuna #7 da auditoria)

## O que entrega

Fecha a **Fase C opcional da Spec 64** (observabilidade): o **log drain** para um
agregador externo (Axiom/Loki — o item "Logtail" da auditoria original é N/A na
stack Java/Spring + Railway). Entrega **três coisas**:

1. **Runbook `ops/log-drain.md`** — onde os logs vivem (Railway), janela de
   retenção da plataforma, como habilitar o **log drain** (Railway → Observability)
   e — o mínimo exigido pela Spec 64 — **como buscar por `X-Request-Id`** e
   cruzar com `platform_activity_log` (SQL) e métricas (`/actuator/prometheus`).
2. **Seção nova no `deploy/railway.md`** — passo a passo da configuração do
   drain (destino Axiom/Loki free tier), sem segredos no repo (a credencial do
   drain vive no Railway, nunca no código/git).
3. **Job de retenção/expurgo do `platform_activity_log`** (padrão Spec 53,
   espelho do `JobRunsCleanupScheduler`) — **opt-in por config (default
   DESLIGADO)**: preserva o contrato **append-only** da Spec 41 (a API não ganha
   rota de mutação); o operador habilita quando o volume exigir.

**Estado atual (o que JÁ existe e não será refeito):** logs estruturados em prod
(`logback-spring.xml` — `ts/level/logger/requestId/traceId/spanId/msg`, 1 linha
por evento, parseável por Loki/Datadog/ELK); `X-Request-Id` injetado no MDC pelo
`RequestIdFilter` + `traceId/spanId` do Micrometer Tracing (Spec 57);
`/actuator/prometheus` (Spec 53); trilha `platform_activity_log` (Spec 41, com
índice `ix_platform_activity_created`); runbook de incidente
`ops/security-incident.md` (Spec 64). A **lacuna** é: drain externo configurado +
documentação de consulta + retenção da trilha.

## Contexto (por que agora)

Auditoria de segurança 2026-08-14 (lacuna #7): a trilha e os app logs existem,
mas a **agregação externa não está configurada** — investigação pós-incidente
depende da UI do Railway (janela de retenção limitada) e de consulta manual ao
Postgres. Com o log drain + runbook de consulta, o operador busca por
`X-Request-Id` no agregador e cruza com a trilha/métricas — fechando o critério
8 da Spec 64 ("logs investigáveis").

## Critérios de aceite

1. **Runbook `ops/log-drain.md`** — cobre: (a) onde os logs vivem no Railway
   (aba Logs do serviço; janela de retenção da plataforma anotada); (b) como
   habilitar o **log drain** (passo a passo Railway → Observability; destino
   Axiom/Loki free tier; credencial fora do repo); (c) **busca por
   `X-Request-Id`** no agregador; (d) **correlação**: `X-Request-Id` ↔
   `platform_activity_log` (SQL por `created_at`/`action`/`actor_id`) ↔ métricas
   (`/actuator/prometheus`).
2. **`deploy/railway.md`** — seção "Log drain (opcional)" com o passo a passo e
   a nota de segredos (nada de credencial no git; os logs estruturados já saem
   mascarados pelo `SensitiveDataLoggingFilter` — sem senha/token/segredo em
   claro no drain).
3. **Job de retenção opt-in** — `PlatformActivityCleanupScheduler`
   (`@ConditionalOnProperty foxcash.scheduler.activity-log-cleanup-enabled=true`,
   default **desligado** — o contrato append-only da Spec 41 permanece por
   default); `@Scheduled` + `@SchedulerLock` + `JobExecutionService.run`
   (padrão Spec 53, espelho do `JobRunsCleanupScheduler`); porta
   `PlatformActivityRepository.deleteOlderThan(cutoff)` + implementação JPA
   (`@Modifying` delete por `created_at < cutoff`, usando o índice existente).
4. **Config** — `foxcash.scheduler.activity-log-cleanup-enabled` (default
   `false`), `foxcash.scheduler.activity-log-cleanup-cron` (default
   `0 15 4 * * *`), `foxcash.scheduler.activity-log.retention-days` (default
   `180`); documentadas no runbook.
5. **Sem regressão** — `mvn test` verde; o job desligado por default não muda
   nenhum comportamento existente; a trilha continua sendo gravada normalmente
   (o delete é exclusivo do job, sem rota de API).
6. **Teste unitário** — `PlatformActivityCleanupSchedulerTest`: com o job
   ativo, deleta mais velhos que o cutoff via `deleteOlderThan` e registra no
   `JobExecutionService` (mock); cutoff = `now - retentionDays`.

## Dependências

- **Spec 64** (observabilidade/incidente — critério 8 "logs investigáveis").
- **Spec 41** (`platform_activity_log` — trilha append-only; o job NÃO toca a
  API/contrato).
- **Spec 53** (`JobExecutionService` + ShedLock + `/actuator/prometheus`).
- **Spec 57** (`traceId`/`spanId` no log estruturado).

## Fora de escopo

- **Configurar o drain de fato no Railway** — é passo operacional (acesso do
  operador); a spec entrega a documentação completa do passo a passo.
- **Migrar para ELK/Splunk** — o formato estruturado já é compatível; a spec
  documenta o Axiom/Loki free tier (decisão Spec 64).
- **Mudar o formato dos logs** — o `logback-spring.xml` já é estruturado em
  prod (sem mudança).
- **Rotação de backup do Postgres** — é a Spec 96 (cron), não esta.
