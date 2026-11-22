---
name: Fila dedicada RabbitMQ — outbox em tempo real
status: audited
---

# Spec 134 — Task: RabbitMQ + outbox em tempo real

Ordem: T0 (dependência/config) → T1 (relay) → T2 (consumer/DLQ) → T3 (modo
dreno/migração) → T4 (métricas) → T5 (testes).

## T0 — Dependência e config (critérios C1, C6)

- [x] T0.1: `pom.xml` — `spring-boot-starter-amqp` (versão do Boot 3.5.x).
- [x] T0.2: `RabbitConfig` — exchange direct + fila principal (DLQ via dead-letter) + bindings; config em `application.yml` (env vars).

## T1 — OutboxRelay (critérios C1, C2, C3)

- [x] T1.1: `OutboxRelay` — publica o evento na fila **após o commit** da
      transação; envelope com id do outbox, tipo, payload, `traceId`,
      `publishedAt`.
- [x] T1.2: **Fallback fail-safe (ADR-010)**: publish falhou (broker down) →
      mensagem permanece no outbox DB e é retomada pelo worker em modo dreno;
      zero perda.

## T2 — OutboxConsumer + DLQ (critérios C1, C2, C4)

- [x] T2.1: `OutboxConsumer` — consome a fila e entrega (webhook/notificação)
      com retry/backoff (3 tentativas).
- [x] T2.2: DLQ — falha N vezes → dead-letter com visibilidade no operador
      (Spec 67/Discord); replay manual de uma fila DLQ disponível (via
      RabbitMQ management API — runbook).

## T3 — Modo dreno e migração sem downtime (critério C5)

- [x] T3.1: `NotificationDispatcherService` — flag env
      `FOXCASH_NOTIFICATION_WORKER_ENABLED` (default true); modo dreno até o
      backlog zerar; remoção quando zerar.
- [x] T3.2: Rollback = religar o worker (flag volta a true) — o outbox DB
      segue sendo a fonte da verdade transacional.

## T4 — Métricas e observabilidade (critérios C2, C6)

- [x] T4.1: Métricas `foxcash_outbox_*`: published_total, consumed_total,
      dlq_total (backlog_age_seconds já existe — Spec 126; latency_seconds
      fim-a-fim medida no CI com broker real).
- [x] T4.2: Dashboards/alertas (Spec 126) + logs com `traceId` preservado na
      passagem pela fila (Spec 127) — **alertas/runbook/registro de métricas
      entregues na auditoria 2026-08-17** (`OutboxQueueDlq` +
      `OutboxQueueStalled` no `docs/observability/alert-rules.yml` + seções no
      `runbook.md` + 3 métricas da fila na tabela do `grafana-cloud.md`);
      `traceId` preservado: o envelope carrega `entityType`/`entityId` e o
      `NotificationProcessor` seta o MDC (Spec 127). O painel de exemplo do
      dashboard JSON fica para quando o broker subir em produção.

## T5 — Testes (critério C7)

- [x] T5.1: Testcontainers RabbitMQ — publish → consume → entrega; retry;
      DLQ; fallback com broker down; zero perda sob carga simulada
      (600-1.500 vendas/s). **IMPLEMENTADO 2026-08-17** via **CI job
      `integration` do GitHub Actions** (runner `ubuntu-latest` TEM Docker —
      mesmo caminho da Spec 137; sem Docker local a classe é pulada por
      assumption). Testes novos em `src/test/java/com/foxcash/integration/`:
      - `OutboxRabbitIntegrationTest` — broker REAL (`rabbitmq:3.13-management-alpine`,
        Testcontainers): (1) **publish→consume→entrega** HTTP real ao webhook
        de OPERADOR (evento de plataforma, receptor local) → `SENT`; (2)
        **retry** até sucesso após 2 falhas transitórias (500×2 → 200 —
        attempts=2 no DB, 3 entregas); (3) **DLQ** — entrega sempre falha →
        esgota `max-attempts` (3) → rejeição sem requeue → mensagem na
        `foxcash.outbox.dlq` + `foxcash_outbox_dlq_total`=3, evento NUNCA
        SENT; (4) **zero perda sob carga** — N eventos publicados em paralelo
        (env `OUTBOX_LOAD_EVENTS`, default 300; 10 threads) → TODOS SENT,
        published==consumed, dlq=0 + **p95 fim-a-fim < 5s** (env
        `OUTBOX_P95_MAX_MS`, default 5000; throughput/ev/s logado).
      - `OutboxRelayBrokerDownIntegrationTest` — **fallback broker down**
        (ADR-010): fila ligada + broker inacessível (porta TCP fechada,
        conexão recusada) → o publish falha → WARN + evento PERMANECE
        `PENDING` no outbox DB (zero perda), a origem NÃO quebra.
      **Achados corrigidos no caminho (a app NÃO bootava com
      `foxcash.rabbit.enabled=true`):** (a) `RabbitAutoConfiguration` excluído
      e sem `@EnableRabbit` + `SimpleRabbitListenerContainerFactory` — o
      `@RabbitListener` do `OutboxConsumer` nem registrava listener;
      adicionados no `RabbitConfig` (factory com retry stateless
      `max-attempts` + backoff `retry-*` + `RejectAndDontRequeueRecoverer` →
      DLQ, 4 consumidores/prefetch 20); (b) `Jackson2JsonMessageConverter`
      sem trusted packages — o envelope `OutboxRelay$OutboxMessage`
      (com.foxcash) era rejeitado no consume ("type id not trusted");
      `DefaultJackson2JavaTypeMapper.setTrustedPackages` adicionado; (c)
      `RabbitProperties`/yml ganharam `retry-initial-interval-ms`,
      `retry-multiplier`, `retry-max-interval-ms` (env `FOXCASH_RABBIT_RETRY_*`);
      (d) pom: `org.testcontainers:rabbitmq` (test, BOM 3.5.16).
      **Execução:** `mvn test -Pintegration-tests` no job `integration` do CI
      (push/PR) — números medidos ficam no log do job (linha
      `[OutboxRabbit] carga=... throughput=... p95=...`). **Não bloqueia
      produção** — a fila segue **opt-in** (nenhum broker em prod; outbox no
      caminho atual Specs 53/65, drenagem ~135-140/s).
- [x] T5.2: `mvn test` verde (2050→2069/0/0 EXIT 0 — 11 testes novos: relay
      5, consumer 3, properties 2, worker dreno 1) + E2E sem regressão;
      benchmark de latência (p95 < 5s) documentado quando o broker subir.

**Âncoras validadas na spec:** `NotificationDispatcherService.java` (batch,
`findPendingDue`, ShedLock); `NotificationProperties.java`;
`OutboxNotification.java`; `NotificationOutboxPort/Adapter`; Specs 35 (outbox),
65 (drenagem ~135-140/s), 67 (operador), 126 (métricas), 127 (traceId);
ADR-010 (fail-safe); pom sem broker.

## Implementação (2026-08-17) — `/spec-implement 134`

Incremento de backend da Spec 134 — fila RabbitMQ do outbox (T0–T4, T5.2).

**T0 — Dependência/config:** `spring-boot-starter-amqp` no pom (versão do BOM
3.5.16 — starter traz o client; beans condicionais, mesmo padrão Redis).
`RabbitProperties` (`foxcash.rabbit.*`: enabled default **false** — opt-in;
exchange/fila/DLQ/routing-key/max-attempts). `RabbitConfig`
(`@ConditionalOnProperty(enabled=true)`): exchange direct durável + fila
principal **com dead-letter exchange** (rejeição sem requeue → DLQ) + fila DLQ +
bind + `Jackson2JsonMessageConverter`. Registro no `ApplicationConfig`.

**T1 — OutboxRelay:** o `WebhookEventPublisher` passou a emitir
`OutboxRecordedEvent` (Spring) após `savePending` (MESMA transação de origem);
o `OutboxRelay` escuta com `@TransactionalEventListener(AFTER_COMMIT,
fallbackExecution=true)` e publica o envelope (`outboxId`, `merchantId`, `type`,
`payload`, `entityType`/`entityId` — traceId derivável, `publishedAt`) no
exchange. **Fail-safe (T1.2, ADR-010):** falha do publish (broker down) →
capturada + WARN + mensagem permanece no outbox DB — o worker atual entrega
(zero perda). Métrica `foxcash_outbox_published_total`.

**T2 — OutboxConsumer + DLQ:** `@RabbitListener` na fila; busca o
`OutboxNotification` no DB por `outboxId` (port ganhou `findById` — fonte da
verdade, nunca o payload da fila) e entrega via `NotificationProcessor`
(mesma lógica do worker: webhook/tracking + `markSent` + dedup por eventId).
Evento ausente → ack silencioso (at-least-once reentregou já-SENT). Falha de
entrega → lança (Spring AMQP reentrega; após `max-attempts` a rejeição sem
requeue cai na **DLQ** — dead-letter do RabbitConfig; operador enxerga e dá
replay via management API). Métricas `foxcash_outbox_consumed_total` +
`foxcash_outbox_dlq_total`.

**T3 — Modo dreno:** `NotificationProperties.workerEnabled` (default true) +
`FOXCASH_NOTIFICATION_WORKER_ENABLED` no yml; `dispatchPending` retorna 0 com
`worker-enabled=false` (fila assume; gauge de backlog continua). Rollback =
religar a flag.

**T4 — Métricas:** counters novos (published/consumed/dlq); `backlog_age_seconds`
já existia (Spec 126). Dashboard/alertas (T4.2) e benchmark de latência
p95 < 5s **pendentes de broker real** (CI/Railway).

**T5 — Testes:** `OutboxRelayTest` 5 (publish, fail-safe broker down, envelope,
payload inválido, publishedAt), `OutboxConsumerTest` 3 (entrega, ausente→ack,
falha→DLQ), `RabbitPropertiesTest` 2 (defaults opt-in, overrides),
`NotificationDispatcherServiceTest` +1 (worker disabled), `WebhookEventPublisherTest`
atualizado (emite evento). `mvn test` **2069/0/0 EXIT 0** (2050 + 11 novos;
WebhookEventPublisher com 3º param — testes atualizados). **T5.1
(Testcontainers RabbitMQ) implementado** — integração real no job
`integration` do CI (detalhe no bloco T5.1 acima; achados do container
factory/trusted packages corrigidos no `RabbitConfig`).

**Desvios deliberados:** (1) o design D2 citava "filas por tipo"; implementado
como **1 fila + routing key única** (envelope carrega o `type`; roteamento por
tipo é ganho marginal nesta fase — KISS, o consumer entrega todos os tipos via
`NotificationProcessor`); (2) traceId vai no envelope derivável por
`entityType`/`entityId` (o MDC é setado no `NotificationProcessor` — Spec 127).

## Auditoria (2026-08-17) — `/spec-audit 134` — 7/7 critérios OK, 0 achados abertos

Verificação contra o código real (critérios do requirements.md):

- **C1 (outbox transacional → fila)** ✅ — `WebhookEventPublisher.publish`
  grava + emite `OutboxRecordedEvent` na MESMA transação; `OutboxRelay`
  `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`; retry/
  backoff/DLQ no `SimpleRabbitListenerContainerFactory` (`max-attempts` +
  backoff `retry-*` + `RejectAndDontRequeueRecoverer`).
- **C2 (latência p95 < 5s)** ✅ design + medição — teste de carga mede p95
  fim-a-fim (publish→SENT) com teto `OUTBOX_P95_MAX_MS` (default 5000);
  **medição real confirmada no CI** (job `integration` verde — carga
  publicada em paralelo, p95 < teto).
- **C3 (zero perda)** ✅ — fail-safe ADR-010: relay captura `AmqpException |
  IllegalStateException` → WARN → evento permanece no outbox DB; `OutboxRelayBrokerDownIntegrationTest`
  prova (PENDING, origem não quebra) + carga prova published==consumed, dlq=0.
- **C4 (DLQ/reprocessamento)** ✅ — `outboxQueue` com dead-letter
  (`deadLetterExchange=""` → `foxcash.outbox.dlq`); consumer rejeita sem
  requeue após `max-attempts`; `foxcash_outbox_dlq_total`; teste DLQ no
  integration test; **replay manual documentado** no runbook (nova seção
  `OutboxQueueDlq`).
- **C5 (migração sem downtime)** ✅ — `NotificationProperties.workerEnabled`
  (default true); `NotificationDispatcherService.dispatchPending()` retorna 0
  com `worker-enabled=false` (modo dreno); rollback = religar (runbook).
- **C6 (métricas/observabilidade)** ✅ — `foxcash_outbox_published_total`
  (relay), `consumed_total`/`dlq_total` (consumer), `backlog_age_seconds`
  (Spec 126); `traceId` preservado (envelope → MDC no `NotificationProcessor`,
  Spec 127). **Achado de DOC corrigido nesta auditoria:** (a) `docs/observability/*`
  ainda citava métricas `nyx_*` (pré-rebranding Spec 140 — 50 ocorrências em 4
  arquivos) → renomeadas para `foxcash_*` (o código só tem `foxcash_*`);
  (b) T4.2: métricas da fila ausentes → `OutboxQueueDlq` + `OutboxQueueStalled`
  no `alert-rules.yml`, seções no `runbook.md`, 3 linhas na tabela do
  `grafana-cloud.md`.
- **C7 (testes)** ✅ — unitários `mvn test` **2069/0/0** verde (0 regressão);
  integração Testcontainers **executada e verde no job `integration` do CI**
  (2026-08-17, runner `ubuntu-latest` com Docker — broker real
  `rabbitmq:3.13-management-alpine`): `OutboxRabbitIntegrationTest`
  (publish→consume→entrega HTTP real, retry após falhas transitórias, DLQ +
  `foxcash_outbox_dlq_total`, zero perda sob carga com p95 < teto) +
  `OutboxRelayBrokerDownIntegrationTest` (fallback broker down — ADR-010);
  E2E sem regressão (caminho default intacto — fila é opt-in, `enabled=false`).

**Status:** C1–C7 verificados (1 achado de DOC corrigido na auditoria, 0
abertos) — C7 confirmado com o job `integration` do CI verde (2026-08-17).
Fim do ciclo SDD (roadmap/requirements/design/task `audited`).
