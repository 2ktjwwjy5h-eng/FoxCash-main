---
name: Fila dedicada RabbitMQ — outbox em tempo real
status: audited
---

# Spec 134 — Design: RabbitMQ + outbox em tempo real

## D1 — Conceito

O outbox atual (Specs 35/65) é tabela Postgres poliada pelo
`NotificationDispatcherService` (batch 500, `findPendingDue`, runs com lock
ShedLock — `max-batches-per-run`/`max-batch-size` via `NotificationProperties`).
Drenagem medida ~135-140 eventos/s; backlog de 737k PENDING (capacidade)
drena em horas — sem tempo real. **Decisão do usuário (2026-08-16): RabbitMQ**
(AMQP, `spring-boot-starter-amqp`) — integração nativa, adequado ao volume,
mais simples que Kafka.

## D2 — Arquitetura: outbox transacional + relay para a fila (C1)

O padrão outbox **não muda** (evento gravado na MESMA transação do negócio —
consistência garantida). O que muda é o **relay**:

```
[ Transação de negócio ] → outbox DB (inalterado)
        ↓ (mesma transação, se possível) ou worker curto
[ OutboxRelay (novo) ] → RabbitMQ exchange → filas por tipo (webhook/notif/bot/...)
        ↓ retry/backoff → DLQ (dead-letter)
```

- **Relay in-process** (após commit da transação, publicado no mesmo request)
  com fallback: se o publish falhar (broker down), a mensagem **permanece no
  outbox DB** (política ADR-010 fail-safe) e é retomada pelo worker atual.
- **Worker atual** (`NotificationDispatcherService`) continua em **modo dreno**
  (C5 — coexistência): processa o backlog restante enquanto a fila drena; a
  remoção total acontece quando o backlog zera (flag env).

## D3 — Componentes novos (C1, C2, C6)

| Componente | Responsabilidade |
|---|---|
| `RabbitConfig` (novo) | exchange + filas por tipo + DLQ + bindings; connection factory com retry/backoff |
| `OutboxRelay` (novo) | publica o evento na fila após commit; **0 perda** — fallback no outbox DB |
| `OutboxConsumer` (novo) | consome a fila → entrega (webhook/notificação) com retry/backoff e DLQ após N falhas |
| `NotificationDispatcherService` | modo dreno (C5) → remoção por flag |

- **Mensagem**: envelope com `id` do outbox, tipo, payload, `traceId` (Spec
  127 preservado na passagem pela fila) e `publishedAt`/`deliveredAt` (para a
  métrica de latência fim-a-fim).
- **Métricas** (C6): `foxcash_outbox_published_total`, `_consumed_total`,
  `_dlq_total`, `_backlog_age_seconds`, `_latency_seconds` (p95 < 5s — C2).

## D4 — Zero perda sob carga (C3)

- Contador reconciliado: eventos **publicados** (outbox DB) vs **consumidos**
  (fila) — teste de benchmark (600-1.500 vendas/s) com 0 eventos perdidos.
- **Falha do RabbitMQ** → mensagens permanecem no outbox DB (fallback
  transacional) e são reentregues quando o broker volta (fail-safe, ADR-010).

## D5 — DLQ e reprocessamento (C4)

- Mensagem que falha N vezes (retry/backoff, ex.: 3 tentativas) → **DLQ** com
  visibilidade no painel/operador (Spec 67/Discord).
- Replay manual de uma fila DLQ: script/endpoint interno que re-publica as
  mensagens da DLQ na fila original.

## D6 — Migração sem downtime (C5)

- **Coexistência**: relay publica na fila E o worker antigo segue em modo
  dreno até o backlog zerar; depois o worker antigo é **removido** (flag env
  `NYX_OUTBOX_WORKER_ENABLED=false`).
- **Rollback** = religar o worker antigo (flag volta a `true`) — o outbox DB
  continua sendo a fonte da verdade transacional.

## D7 — Testes (C7)

- Testcontainers RabbitMQ: publish → consume → entrega; retry (falha →
  retry → sucesso); DLQ (falha N vezes → dead-letter); fallback com broker
  down (mensagem fica no outbox DB e é reentregue); zero perda sob carga
  simulada.
- `mvn test` verde + E2E sem regressão.

## D8 — Arquivos

| Arquivo | Mudança |
|---|---|
| `pom.xml` | `spring-boot-starter-amqp` (Spec 130 compatível) |
| `src/main/java/.../infrastructure/rabbit/RabbitConfig.java` | novo (D3) |
| `src/main/java/.../application/service/OutboxRelay.java` | novo (D3) |
| `src/main/java/.../application/service/OutboxConsumer.java` | novo (D3) |
| `src/main/java/.../application/service/NotificationDispatcherService.java` | modo dreno + flag (D6) |
| `src/main/resources/application.yml` | config Rabbit (exchange/filas/DLQ/retry) |
| métricas/alertas (Spec 126/docs) | filas + latência (D3) |

## D9 — Fora de escopo

Kafka (alternativa descartada por decisão do usuário); PgBouncer/pool
(Spec 133 — independente); multi-região (Spec 135).

**Âncoras validadas na spec:** `NotificationDispatcherService.java`
(batch 500 via `findPendingDue`, `max-batches-per-run`/`max-batch-size`);
`NotificationProperties.java`; `OutboxNotification.java`; `NotificationOutboxPort/Adapter`
(+ JPA entity/repository); Specs 35 (outbox), 65 (drenagem ~135-140/s), 67
(operador), 126 (métricas), 127 (traceId); ADR-010 (fail-safe); pom sem broker
hoje.
