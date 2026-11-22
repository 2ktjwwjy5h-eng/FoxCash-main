---
name: Fila dedicada RabbitMQ — outbox em tempo real
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/65-otimizacao-charge-outbox/requirements.md"
---

# Spec 134 — Fila dedicada RabbitMQ: outbox em tempo real

## O que entrega

O outbox atual (Specs 35/65) é uma tabela Postgres poliada por worker
(`NotificationDispatcherService`, batch 500/poll PT10S). Medido: drenagem
~135-140 eventos/s (pós-Spec 65), backlog de **737k PENDING** registrado na
campanha de capacidade — drena em horas, sem tempo real. Para "nota 10" em
escala/resiliência, os eventos secundários (webhook PSP, notificação, bot,
e-mail, delivery, tracking) devem fluir com latência de segundos, não horas.

**Decisão do usuário (2026-08-16): RabbitMQ** (AMQP) — integração nativa com
Spring (`spring-boot-starter-amqp`), adequado ao volume atual (~1.500 vendas/s),
mais simples de operar que Kafka.

## Critérios de aceite

**C1 — Outbox transacional → fila.** O padrão outbox continua garantindo a
consistência (evento gravado na MESMA transação do negócio — nada se perde),
mas o **relay** publica na fila RabbitMQ imediatamente (in-process ou worker
curto), em vez de só deixar na tabela. Entrega com retry/backoff/DLQ.

**C2 — Latência de entrega.** Evento publicado na fila → consumido e entregue
(webhook/notificação) com **p95 < 5s** (antes: ~horas). Medido com métrica de
latência fim-a-fim (a base já existe: `nyx_outbox_backlog_age_seconds` →
`foxcash_outbox_*`).

**C3 — Zero perda.** Sob carga (benchmark 600-1.500 vendas/s): 0 eventos
perdidos — reconciliar contador de eventos publicados vs. consumidos. Falha do
RabbitMQ → mensagens permanecem no outbox DB (fallback transacional) e são
reentregues quando volta (fail-safe, política ADR-010).

**C4 — DLQ e reprocessamento.** Mensagens que falham N vezes (retry/backoff)
vão para dead-letter com visibilidade no painel/operador (Spec 67/Discord).
Replay manual de uma fila DLQ disponível.

**C5 — Migração sem downtime.** Coexistência: o relay publica na fila E o
worker antigo continua (em modo dreno) até o backlog zerar; depois o worker
antigo é removido. Rollback = religar o worker antigo (env flag).

**C6 — Métricas/observabilidade.** Métricas de fila: publicadas, consumidas,
DLQ, latência, backlog. Dashboards/alertas atualizados (Spec 126 + docs/
observability). Logs estruturados com `traceId` (Spec 127) preservados na
passagem pela fila.

**C7 — Testes.** Testes de integração com RabbitMQ (Testcontainers): publish →
consume → entrega; retry; DLQ; fallback com broker down; zero perda sob carga
simulada. `mvn test` verde + E2E sem regressão.

## Evidência-base (2026-08-16)

- Spec 65: drenagem ~135-140/s, backlog 737k PENDING, 0 FAILED.
- Capacity report §11.11: outbox não acompanha carga (2.06M PENDING em pico,
  documentado como limite §6).
- Sem broker hoje: 0 de Kafka/RabbitMQ/ActiveMQ no pom.

## Dependências

- **Spec 35** (outbox) — envelope/retry/backoff existentes.
- **Spec 65** (drenagem) — otimizações atuais.
- **Spec 130** — o Boot 3.5.x traz `spring-boot-starter-amqp` compatível.

## Fora de escopo

- Kafka (registrado como alternativa descartada por decisão do usuário).
- PgBouncer/pool — Spec 133 (independente).
