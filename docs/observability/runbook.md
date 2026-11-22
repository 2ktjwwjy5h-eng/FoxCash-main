# Runbook — Alertas proativos (Spec 126)

> O que fazer quando cada alerta disparar. Ordem recomendada em incidente:
> **1) confirmar** (o alerta é real?), **2) mitigar** (parar o sangramento),
> **3) diagnosticar** (trace/logs), **4) resolver** (ação), **5) registrar** (auditoria).

Ferramentas usadas em todos os runbooks:

| Ferramenta | Onde | Para quê |
|---|---|---|
| Trace de transação | `GET /api/v1/master/trace/transactions/{ref}` (Spec 125) | Jornada completa de uma cobrança |
| Logs agregados | Axiom (Spec 95) | Correlação por `requestId` / `traceId` |
| Trilha de auditoria | Spec 41 (`audit_logs`) | Quem fez o quê (inclusive SYSTEM) |
| Job runs | Spec 53 (`job_runs`) | Última execução/falha de cada job |
| Recurso master | Console master (Spec 5) | Ações manuais de correção |

---

## ReconciliationDivergence — divergência de saldo aberta

**Sintoma**: `foxcash_reconciliation_open_issues > 0`. Wallet de um lojista não bate
com o ledger (Spec 30).

**O que fazer**:
1. Abra a lista de issues OPEN (master → reconciliação). Para cada issue: lojista,
   `expectedBalance` (ledger) vs `actualBalance` (wallet).
2. Correlacione com o trace (Spec 125) das transações recentes do lojista e com
   o `job_runs` da última reconciliação (Spec 53).
3. Determine a causa: transação aprovada sem crédito, refund sem débito, ajuste
   manual, etc.
4. Corrija **via operação oficial** (ajuste de saldo com motivo + auditoria Spec
   41). Nunca edite `wallet_balance` direto no banco.
5. Marque a issue `RESOLVED` após confirmar o saldo.
6. Se o padrão se repetir em vários lojistas → bug no `WalletReconciliationScheduler`
   ou no fluxo de crédito/débito (Spec 14/27) — abra bug, não contorne.

---

## WebhookAmountDivergence — valor divergente no webhook PAID (B-05)

**Sintoma**: PSP informou `amount` diferente do cobrado. A transação **não foi
aprovada** (no-op + issue de reconciliação).

**O que fazer**:
1. Veja o trace da transação (Spec 125): valor registrado vs. valor do PSP.
2. Decida: erro do PSP (valor correto é o local) ou erro de integração (o
   `amount` do payload está sendo lido errado).
3. Se o valor local estiver certo → aprovada? **Não**: o fluxo B-05 é
   conservador de propósito. Aprovação manual = operação explícita (com auditoria).
   O pagamento real no PSP deve ser conferido antes (o cliente pagou X, não Y).
4. Se for bug de parsing → corrija o mapeador do webhook e **escreva teste de
   regressão** (é caminho de dinheiro).
5. Monitore o contador: divergências repetidas de um mesmo provedor = bug de
   integração daquele PSP.

---

## OutboxStalled — entrega de eventos parada

**Sintoma**: `foxcash_outbox_backlog_age_seconds > 300`. Nenhum PENDING entregue há
mais de 5 min. **O webhook do lojista (SALE_PAID etc.) não está saindo.**

**O que fazer**:
1. Confirme o worker: logs do `NotificationDispatcherService`/`NotificationProcessor`
   no Axiom (Spec 95) e o `job_runs` do job do outbox (Spec 53).
2. Causas comuns:
   - **Scheduler travado** (deadlock/exception não capturada) → reinicie o worker
     (Railway restart). O ShedLock (`foxcash-notification-dispatch`) libera após
     `lockAtMostFor` se a instância morreu.
   - **Receptor rejeitando** (webhook do lojista com 5xx/cert expirado) →
     `foxcash_outbox_failed_total` subindo; o backoff exponencial (Spec 35) está
     segurando as tentativas — confira o endpoint do lojista.
   - **Banco lento/locked** (o `findPendingDue` fica bloqueado) → ver Hikari e
     locks no Postgres.
3. Se o outbox acumular: **não drene manualmente às cegas** — verifique se o
   receptor aceita (at-least-once + idempotência por `eventId`, Spec 35).
4. Eventos que esgotaram viram `FAILED` e geram `OUTBOX_DEAD` (Spec 53) — o
   operador já foi avisado pelo webhook de operador. Se houver `OUTBOX_DEAD`
   para eventos críticos (SALE_PAID), avalie re-publicação manual com auditoria.

---

## OutboxFailureRate — taxa de falha de entrega alta

**Sintoma**: `failed/(delivered+failed) > 20%` em 15 min.

**O que fazer**:
1. Veja `foxcash_outbox_failed_total{...}` — mas **sem tag de receptor**, então
   correlacione pelos logs (Axiom, busca `NotificationProcessor`) para achar o
   destino falhando.
2. Destino provável: webhook do lojista (cert expirado, endpoint 5xx, timeout) —
   contate o lojista ou ajuste o webhook.
3. Se a taxa não ceder com o backoff, considere pausar as tentativas do destino
   problemático (se houver controle) para não gastar I/O à toa.

---

## OutboxQueueDlq — mensagem caiu na DLQ do RabbitMQ (Spec 134)

**Sintoma**: `foxcash_outbox_dlq_total` incrementado. Uma mensagem esgotou as
`foxcash.rabbit.max-attempts` tentativas (retry/backoff `foxcash.rabbit.retry-*`)
e foi rejeitada **sem requeue** → dead-letter (`foxcash.outbox.dlq`). O evento
NÃO foi entregue; o registro no outbox DB segue `PENDING` (fonte da verdade).

**O que fazer**:
1. Identifique o evento: inspecione o payload da mensagem na DLQ (envelope
   carrega `outboxId` — cruze com o outbox DB e o trace, Spec 125).
2. Corrija a causa (receptor 5xx/cert expirado, payload inválido, etc.).
3. **Replay manual** (RabbitMQ management API): mova a mensagem da DLQ de volta
   para `foxcash.outbox.queue` — o consumer reentrega (at-least-once +
   idempotência por `eventId`, Spec 35). Alternativa: publique de novo no
   exchange `foxcash.outbox` com routing key `outbox`.
4. Se a DLQ encher de um MESMO tipo/destino, é bug de integração — trate na
   origem antes de re-publish.

## OutboxQueueStalled — fila RabbitMQ parada (Spec 134)

**Sintoma**: `foxcash_outbox_published_total` subindo sem
`foxcash_outbox_consumed_total` acompanhar (divergência > 0 por 15 min). O relay
publica, mas o consumer não entrega.

**O que fazer**:
1. Confirme o broker: RabbitMQ management UI — a fila `foxcash.outbox.queue`
   está acumulando? há consumer ativo/conectado?
2. Confirme o consumer: logs do `OutboxConsumer`/`NotificationProcessor` no
   Axiom; o container de listener **reconecta sozinho** (recovery) quando o
   broker volta — aguarde um ciclo antes de mexer.
3. **Fail-safe (ADR-010)**: enquanto a fila não entrega, o outbox DB é a fonte
   da verdade — religue o worker DB (`FOXCASH_NOTIFICATION_WORKER_ENABLED=true`,
   modo dreno) para drenar o backlog. Rollback da Spec 134.

---

## PspFallbackSpike — adquirente caindo (fallback disparando)

**Sintoma**: `increase(foxcash_psp_route_total{outcome="fallback"}[15m]) > 10`.
O roteador (Spec 91) está pulando o provedor primário.

**O que fazer**:
1. Identifique o provedor com falha: `sum by (provider) (increase(foxcash_psp_route_total{outcome="fallback"}[15m]))`.
2. Confirme o estado do provedor: health do adquirente (painel do PSP) e erros
   nos logs (Axiom, `PixProviderRouter`).
3. **Não desative o provedor no banco às cegas** — o circuit breaker preventivo
   (D5) já está desviando. Verifique se o fallback está absorvendo (vendas
   continuam criando cobrança) e se há timeout/erro em massa.
4. Se o provedor estiver fora por longa duração:
   - Alinhe com o suporte do adquirente (a conta/provedor pode estar suspenso).
   - Considere reduzir o peso do provedor na `routing.providers` (config) — com
     auditoria (Spec 41).
5. **Vendas durante o incidente**: confira se `foxcash_psp_charge_created_total`
   segue subindo no provedor secundário — se não, o incidente é maior (sem
   capacidade de cobrança).

---

## PspSkippedSpike — circuito aberto (provedor pulado)

**Sintoma**: `increase(foxcash_psp_route_total{outcome="skipped"}[15m]) > 20`.
Circuit breaker preventivo abriu para um provedor.

**O que fazer**:
1. Veja o health registry do provedor (`PixProviderRouter`/`healthRegistry`):
   falhas acumuladas abriram o circuito.
2. Diagnostique a causa raiz (erros 5xx/timeout do PSP) — não apenas "feche o
   circuito". O circuito fechou sozinho com sucessos (half-open).
3. Se o erro for da integração (bug nosso), corrija no código; se for do
   adquirente, aguarde a recuperação monitorando o painel de PSP.

---

## JobFailed — job agendado falhou

**Sintoma**: `increase(foxcash_job_failure_total[15m]) > 0` para `{{job}}`.

**O que fazer**:
1. Veja `job_runs` (Spec 53) para a última execução do job e a mensagem de erro.
2. Logs no Axiom (`job={job} status=FAILED`).
3. Causas comuns:
   - **Reconciliação** (`foxcash-wallet-reconciliation`): banco travado/limite.
     Re-rodar manualmente via endpoint master (se existir) após corrigir.
   - **Outbox** (`foxcash-notification-dispatch`): ver OutboxStalled acima.
   - **Risk snapshot** (Spec 16): provedor/API externa fora.
4. A falha do job **não** para o sistema (o erro é capturado e a próxima run
   tenta de novo), mas se o job for crítico (reconciliação), verifique o efeito
   pendente.

---

## Http5xxSpike — erro 5xx subindo (por rota)

**Sintoma**: `rate(foxcash_http_5xx_total{route="..."}[15m]) > 0.5`.

**O que fazer**:
1. Veja qual rota: `sum by (route) (rate(foxcash_http_5xx_total[5m]))`.
2. Logs no Axiom para essa rota (`status=5xx`, `requestId`).
3. 5xx em rota transacional (webhook/cobrança) = prioridade alta — use o trace
   (Spec 125) para achar a transação afetada.
4. Verifique se é pico transitório (deploy, cold start) ou bug persistente
   (exception em massa — `GlobalExceptionHandler` registra o counter no handler
   genérico).

---

## Http429Spike — rajada de 429 (abuso/rate limit)

**Sintoma**: `rate(foxcash_http_429_total[5m]) > 20` no grupo X.

**O que fazer**:
1. Veja o grupo: `sum by (group) (rate(foxcash_http_429_total[5m]))` —
   `transactional`, rota pública, ou `authenticated` (por principal).
2. **Não é necessariamente ataque**: bot legítimo com polling agressivo
   (`/deliveries/pending`) também gera 429. Confira no Axiom os `remoteAddr`
   (trust-proxy) / principal repetindo.
3. Se for abuso real: o rate limit por IP (Spec 18/58) e por principal (Spec 58)
   já está protegendo. Alerta de segurança (Spec 64) já throttled o operador.
4. Se for bot legítimo: ajuste o polling do cliente ou o teto da rota (config,
   com auditoria).

---

## HikariPoolExhaustion — pool de conexões perto do teto

**Sintoma**: `hikaricp_connections_active / hikaricp_connections_max > 0.9`.

**O que fazer**:
1. Veja `hikaricp_connections_pending` (fila esperando) — se subir, há bloqueio.
2. Causas: query lenta, transação longa, **connection leak**, pico de carga.
3. Logs do Postgres (queries lentas) + Axiom (`requestId` — veja latências).
4. Mitigação imediata: reiniciar a instância libera o pool, mas **não resolve a
   causa raiz**. Procure o padrão (job que prende conexão — ver auditoria
   2026-08-05 do `NotificationDispatcherService`, que já roda por-notificação).
5. Depois: ajuste `spring.datasource.hikari.maximum-pool-size` (capacity-model.md)
   se o teto for pequeno para o baseline.

---

## HeapHigh — heap JVM perto do teto

**Sintoma**: `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes > 0.9`.

**O que fazer**:
1. Confirme GC thrash: `jvm_gc_pause_seconds` alto + CPU alta.
2. `heap dump` via actuator (jolokia/async-profiler) ou `jmap` se houver acesso.
3. Padrões conhecidos: carga de lote grande (outbox/CRM) sem paginação, cache em
   memória crescendo (RateLimitStore memory é por IP — cardinalidade alta),
   `@Async` com fila sem teto.
4. Mitigação imediata: aumentar memória da instância (Railway) ou reiniciar.
   Causa raiz: procure alocação sem liberação (trace de request que cresce).

---

## Incidente sem alerta (algo parece errado, nada disparou)

1. `GET /actuator/health` (readiness) — o orquestrador já vê; confirme UP.
2. Métricas em `docs/observability/dashboard-example.json` (painel Operação).
3. Se a métrica que você precisava **não existe**, é um gap de instrumentação —
   adicione à Spec 126 (novo contador/gauge) em vez de improvisar no incidente.
