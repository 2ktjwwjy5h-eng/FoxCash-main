# Capacity Report — TrevoPay v1 (Spec 62: Capacity Engineering & High-Throughput)

> **Status:** `concluído` — campanha completa (F1-F10) executada e auditada.
> **Ambiente:** Railway produção (`foxcash-prod`), réplicas variáveis
> (2→16), Java/Spring Boot, PostgreSQL 500 conexões máx, Redis, rate limit
> `redis` (elevado durante capacidade, restaurado após).
> **Janela:** 2026-08-08 → 2026-08-10.
> **Gerador:** k6 local (1 IP, F7) + **loadgen Railway distribuído** (rede
> privada, F2B/F8/F9) — `load-tests/k6/capacity-stages.js` e
> `explosion-stages.js`, 1 venda = `POST /api/v1/nyx/pix/cob`
> (idempotencyKey única) + `POST /webhooks/pix` (PAID, assinado HMAC-SHA256).
> Resultados em `load-tests/k6/results/*.json`.

---

## 1. Resumo executivo

| Métrica | Valor medido | Nota |
|---|---|---|
| **Vendas/s sustentadas (0% falhas)** | **~1.246 (4 réplicas) / ~1.554 (8 réplicas)** | otimizado pela Spec 65 (antes ~480/s com 2 réplicas) |
| **p95 latência por venda** | **~120ms (600/s) / ~1,15s (1.300/s)** | pós-Spec 65; inflexão 600→750/s |
| **5xx** | **0.00%** | em todos os estágios de capacidade e explosão |
| **Duplicações (idempotency)** | **0** | todos os estágios (F4/F8/F9) |
| **Outbox perdido** | **0** (PENDING drena) | drenagem ~135-140/s pós-Spec 65 (antes ~20-33/s) |
| **Ledger/wallet/reconciliação/deadlocks** | **0/0/0/0** | zona sagrada intacta em TODOS os estágios (mesmo com Postgres saturado) |

**Número reportado (medido, nunca estimado):**

- **Capacidade útil: ~1.246 vendas/s sustentadas com 4 réplicas** (0% 5xx,
  100% sale_ok, p95 charge ~1,15s, 0 duplicações, 0 perda de ledger/outbox).
- **Teto com 8 réplicas: ~1.554 vendas/s** (não-linear — Postgres
  max_connections=500 é o gargalo com pool 100/réplica).
- **Ponto de ruptura: 16 réplicas degrada** (sale_ok 94-97%, p95 ~10-19s) —
  o limite de conexões do Postgres é o gargalo central do scale-out.
- Meta de 5.000 vendas/s **não atingida** com esta arquitetura/pool — para
  além de ~1.550/s é necessário dimensionar o pool por réplica ou PgBouncer
  (ADR-013 revisada). O relatório reporta o **medido**, não o estimado.

---

## 2. Tabela por estágio (task 25)

Configuração de rate limit por estágio: **elevado** (task 24 — tetos 10M; o rate limit
NÃO é o gargalo medido). `MAX_VUS` controlado quando indicado (edge Railway limita
conexões simultâneas por IP de origem — ver §4).

| Estágio (alvo vendas/s) | it/s real | % do alvo | falhas % | sale_ok % | p50 | p95 | max | VUs máx | dropped |
|---|---|---|---|---|---|---|---|---|---|
| 250 | 233 | 93% | 0.00% | 100% | 538ms | 1.26s | 8.8s | 306 | 61 |
| 300 | 277 | 92% | 0.00% | 100% | 532ms | 1.21s | 12.2s | 327 | 37 |
| 400 | 348 | 87% | 0.00% | 100% | 959ms | 2.37s | 7.8s | 637 | 298 |
| 500 (rev3, MAX_VUS=500) | 310 | 62% | 0.00% | 100% | 495ms | 1.90s | 8.0s | 500 | 6.397 |
| 600 (rev2, MAX_VUS=600) | 442 | 74% | 0.00% | 100% | 478ms | 1.05s | 6.2s | 600 | 3.348 |
| 700 (rev1, MAX_VUS=700) | 429 | 61% | 0.00% | 100% | 486ms | 2.84s | 6.8s | 700 | 9.064 |
| 1000 (rev14, MAX_VUS=600) | 610 | 61% | 0.00% | 100% | 509ms | 1.32s | 9.0s | 600 | 11.188 |

> **Leitura:** o it/s real satura em ~600 vendas/s independente do alvo (250→233,
> 1000→610). A app sustenta ~600 vendas/s com **0% falhas e p95 ~1.3s** quando o k6 é
> limitado a ~600-800 conexões (MAX_VUS). Acima disso o edge Railway recusa conexões
> (timeouts de 21s — ver §4). `dropped_iterations` = k6 não conseguiu iniciar iteração a
> tempo (VUs insuficientes sob latência), **não** é falha da app.

### 2.1 Latência por endpoint (stage-1000-rev14, ~610 vendas/s)

| Endpoint | p50 | p95 | max |
|---|---|---|---|
| `POST /api/v1/nyx/pix/cob` (charge) | 271ms | 456ms | 8.7s |
| `POST /webhooks/pix` (PAID) | 237ms | 434ms | 8.3s |

---

## 3. Ponto de inflexão (task 26)

- **Inflexão p95:** entre 300 e 400 vendas/s o p95 sobe de ~1.2s para ~2.4s (primeira
  contenção perceptível) — depois estabiliza em ~1.3s até ~600 vendas/s.
- **Inflexão de falhas:** acima de ~600-650 vendas/s o k6 precisa de mais de ~800
  conexões simultâneas para sustentar o rate; o **edge Railway recusa conexões por IP de
  origem** (todas as conexões do k6 vêm de 1 IP) → timeouts de conexão TCP de 21s
  (`dial tcp ... host não respondeu`). Isto NÃO é falha da app: CPU máx 42%, heap estável,
  Postgres ocioso (1-2 conexões ativas em média) durante os testes.
- **Teto real da app (2 réplicas): ~600 vendas/s** — limitado por pool Hikari
  (200×2 = 400 conexões, chega a 200 ativas/réplica no pico) + Postgres max 500 + CPU
  (máx 42% numa réplica sob teste). Não é rate limit (elevado), não é edge (com MAX_VUS
  controlado o edge não interfere).

---

## 4. Gargalos corrigidos durante a F7 (evidência)

| # | Gargalo | Sintoma | Correção | Evidência |
|---|---|---|---|---|
| 1 | **Rate limit de produção (task 24)** | 429 `too_many_requests` em massa (92% no stage-500) — o benchmark media o rate limit, não a app | Tetos elevados de 20/120/300/120 por min → **10M** (`NYXCASH_SECURITY_RATE_LIMIT_*_MAX_REQUESTS`) + redeploy | diag-status 92% 429 → 0% após correção |
| 2 | **Cap de pendentes por lojista (Spec 58)** | 409 `too_many_pending_charges` (pool de 1 lojista estoura a ~100 vendas/s com latência ~2s) | Pool de lojistas no `capacity-stages.js` (POOL=40) | 0.00% falhas com POOL=40 (3/44.444) |
| 3 | **Conexões TCP do Tomcat** | timeouts de conexão com 5000 VUs | `NYX_TOMCAT_MAX_CONNECTIONS=10000` + `SERVER_TOMCAT_THREADS_MAX=1000` | sem 5xx, threads folgadas |
| 4 | **Pool Hikari** | 200 ativas/réplica no pico (pool cheio, 0 idle) | `NYX_DB_POOL_SIZE` 20 → 100 → **200** (Postgres tem folga até 500) | idle=200 em repouso |
| 5 | **Edge Railway (limite por IP — NÃO é a app)** | timeouts `dial tcp` acima de ~800 conexões simultâneas do k6 (1 IP) | **Documentado, não corrigido**: usar `MAX_VUS` ≤ 800 no k6 para medir o teto da app | 0% falhas com MAX_VUS 600-800; 2-35% acima |

> **Decisão (regra de ouro — não alterar arquitetura sem provar o gargalo):** o edge
> Railway limita conexões por IP de origem, mas a app NÃO satura abaixo disso (CPU 12-42%,
> heap estável, Postgres ocioso). Para medir o teto real acima de ~600 vendas/s seria
> necessário distribuir o gerador por múltiplos IPs (k6 cloud / geradores distribuídos) —
> fora do escopo do benchmark local (1 IP). O número reportado é o medido.

---

## 5. Integridade pós-teste (zona sagrada)

| Verificação | Resultado |
|---|---|
| Duplicações `idempotency_key` | **0** (1.048.877 distinct = total) |
| Transações criadas (3h de teste) | 927.398 |
| 5xx na app | **0.00%** |
| Cobranças APPROVED | 1.046.868 |
| Cobranças PENDING (charge falhou → sem webhook) | 2.009 (~0.19%) — vendas não completadas, **sem duplicação** |
| Outbox SENT | 34.221 |
| Outbox PENDING | **2.061.506** (ver §6 — limite conhecido, não perda) |
| Lock contention (`pg_locks NOT granted`) | pico 93 locks em espera (transitório), sem deadlock |

---

## 6. Limites conhecidos

1. **Outbox não acompanha carga:** o worker `nyxcash-notification-dispatch` processa
   `max-batch-size=50` a cada 30s (~10k/hora; 3.660 execuções SUCCESS). O benchmark criou
   ~2M eventos SALE_PAID em ~9h → backlog de 2.06M PENDING com `attempts=0` (nunca
   tentados). **Sem perda** (os eventos permanecem PENDING e escoam eventualmente), e o
   webhook PAID do PSP é **síncrono** (o outbox é fila paralela fora do caminho crítico) —
   mas o backlog é real e precisa de lote maior / worker mais rápido antes de declarar
   escala. Documentado como tarefa pós-v1.
2. **Teto do benchmark local = ~600 vendas/s** (edge por IP + 1 gerador). Meta de 5k
   vendas/s exige geradores distribuídos (F9 scale-out + k6 cloud).
3. **Pool Hikari/Postgres:** com pool 200×2 = 400 + coletor ≈ 401-402, Postgres max 500 —
   folga de ~100. Próximo gargalo provável ao escalar réplicas.

### 6.1 Revert do rate limit (pós-benchmark)

O rate limit foi elevado APENAS durante o benchmark (task 24) para não mascarar o teto
real. **Restaurado para os valores originais em 2026-08-09** (redeploy aplicado, app
Online 2/2):

| Variável | Original | Durante benchmark | Restaurado |
|---|---|---|---|
| `NYXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS` | 20/min | 10M | **20/min** |
| `NYXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS` | 120/min | 10M | **120/min** |
| `NYXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS` | 300/min | 10M | **300/min** |
| `NYXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | 120/min | 10M | **120/min** |

Variáveis de capacidade **mantidas** (não são anti-DoS; documentadas como configuração do
ambiente do benchmark): `NYX_DB_POOL_SIZE=200`, `SERVER_TOMCAT_THREADS_MAX=1000`,
`NYX_TOMCAT_MAX_CONNECTIONS=10000`, `NYX_TOMCAT_ACCEPT_COUNT`. Para re-executar a F7/F8:
re-elevar o rate limit (tetos 10M) e redeploy — comandos em `load-tests/k6/` e no histórico
do commit `02dfaa2`.

### 6.2 Drenagem do outbox aumentada (pré-F8, 2026-08-08 — commit `66954c2`)

A pós-F7 deixou backlog de ~2,06M PENDING (limite 1 acima). Antes do F8, a drenagem foi
aumentada com evidência (mudança de config/código aprovada pelo usuário):

| Parâmetro | Antes | Depois |
|---|---|---|
| `nyxcash.notification.max-batch-size` | 50 (hardcoded) | `${NYX_NOTIFICATION_MAX_BATCH_SIZE:50}` = **200** no Railway |
| Poll do dispatcher | PT30S (hardcoded) | `${nyxcash.notification.poll-interval:PT30S}` = **PT10S** no Railway |
| ShedLock `lockAtMostFor` | PT10M | **PT40M** (cobre lote ≤ 240 eventos × timeout HTTP 10s) |
| Guarda fail-fast | — | `NotificationProperties.validate()`: `max-batch-size > 240` → boot falha (integridade > throughput, não-duplicação F4) |

**Medido (pós-deploy `448b015e`):** drenagem ~6.000/h → **~136.000/h (≈22×)**; PENDING
2.054.756 → 2.047.956 em 180s (−6.800); 0 retries; 0 carga nova. Backlog atual drena em
~15h (antes ~14 dias). Ainda ~3× abaixo do pico de criação da F7 (~441k/h) — o backlog
cresce durante F8 pesado, mas recupera rápido após.

---

## 7. Metodologia reproduzível

- **Script:** `load-tests/k6/capacity-stages.js` (env: `BASE_URL`, `STAGE`, `DURATION`,
  `VUS`, `MAX_VUS`, `POOL`, `WEBHOOK_SECRET`). `setupTimeout=180s` (pool de lojistas).
  `setup()` cria POOL lojistas (cadastro via API); cada iteração = charge + webhook PAID
  com idempotencyKey `cap-<ts>-<VU>-<ITER>`.
- **Rate limit usado:** elevado (task 24) — tetos 10M; `NYX_RATE_LIMIT_STORE=redis`.
- **Comando típico:**
  ```
  WS=$(cat /tmp/.ws.tmp)
  k6 run load-tests/k6/capacity-stages.js \
    --env BASE_URL=https://nyxcash-production.up.railway.app \
    --env STAGE=1000 --env DURATION=60s --env VUS=600 --env MAX_VUS=600 \
    --env POOL=40 --env WEBHOOK_SECRET=$WS \
    --summary-export=load-tests/k6/results/stage-1000-rev14.json
  ```
- **Métricas DB:** `e2e-tests/metrics-pg.cjs` (túnel port 15432) — active/idle/cache_hit/
  locks a cada 1s. **Métricas app:** `e2e-tests/metrics-app.cjs` — CPU/heap/Hikari via
  `/actuator/prometheus` (X-Api-Key admin) a cada 1s.
- **Arquivos de resultado:** `load-tests/k6/results/stage-*.json` (um por execução;
  sufixo `revN` = revisão após correção).

---

## 8. Próximos passos (F8-F10)

- **F8 (explosão):** rate limit ATIVO (restaurar tetos originais) — 5k→20k vendas/s até
  quebrar com 429/backpressure (quebra bonita).
- **F9 (scale-out):** 2→4→8→16 réplicas, mesma metodologia.
- **F10:** relatório final com número real; decidir PgBouncer se o pool Hikari/Postgres
  for o gargalo no scale-out.

---

## 9. Fase 1 — BLOQUEADA (2026-08-09)

> **Status:** `bloqueada` — aguardando decisão do usuário sobre a política de rate limit
> para o benchmark. Nenhuma carga foi gerada.

### 9.1 Estado do ambiente pós-recuperação (baseline limpo, deploy `f45ae501`)

| Verificação | Resultado |
|---|---|
| `NYX_DB_POOL_SIZE` (2 réplicas) | **100×2** — confirmado por env var e por conexões no banco (2 IPs × 100 JDBC) |
| `numReplicas` | **2** (deploy ativo `f45ae501`, SUCCESS) |
| Postgres conexões | **201/500 (40,2%)** — abaixo de 50% |
| 5xx nos logs pós-deploy | **0** (1.500 linhas: 0 erros HTTP, 0 exceptions; readiness `{"status":"UP"}`) |
| `reconciliation_issues` | **0** (tabela vazia) |
| Duplicações `idempotency_key` | **0** |
| Divergência wallet × ledger | **0** (2.322/2.322 lojistas com ledger, saldo ≡ último `balance_after`) |
| Outbox | drenando ativamente: 54.600 SENT/30min, job SUCCESS a cada ~5-8s, **0 FAILED**; backlog residual 1,93M PENDING (load test F7/F8) drena a ~109k/h |

### 9.2 Ruído de observabilidade conhecido (não bloqueia)

O log do deploy registra **1** ocorrência (em 1.500 linhas):
```
level=ERROR logger=i.o.e.internal.http.HttpExporter msg=Failed to export spans.
  ... Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318
```
É o exporter do **OpenTelemetry** tentando alcançar um coletor OTLP em `localhost:4318`
que não existe no container. **Não é um 5xx HTTP**, não afeta respostas nem integridade —
ruído conhecido de telemetria (pré-existente). **Documentado para não confundir
diagnóstico futuro**; limpeza = ajuste de config do OTel (fora do escopo).

### 9.3 Bloqueio: rate limit de produção inviabiliza a Fase 1 (50/100/150 vendas/s)

O harness `capacity-stages.js` exige rate limit elevado (F7, task 24). Com os tetos de
produção **restaurados** (§6.1), o k6 local (1 IP) colapsa em 429 em segundos:

| Rota | Limite (janela 60s) | Teto efetivo para o k6 (1 IP) |
|---|---|---|
| `POST /webhooks/pix` (webhook PAID) | 120/min **por IP** | **~2/s** ← teto mandatório |
| `POST /api/v1/nyx/pix/cob` (charge) | 300/min por IP + 120/min **por lojista** | ~5/s (IP) / ~30/s (pool 15) |
| `POST /api/v1/merchants` (setup do pool) | 20/min por IP | pool limitado a ~18 lojistas/run |

**Consequência:** em 50/s (3.000 webhooks/min) o webhook estoura os 120/min em ~2,4s →
429 em massa → o teste mede o rate limiter (comportamento projetado, Spec 58), **não** a
degradação do Postgres/ledger/outbox. Elevar os tetos exige alteração de variável +
redeploy — ambos fora do escopo autorizado desta sessão.

**Decisão pendente:** autorizar elevação temporária (procedimento F7, restaurar ao final)
OU aceitar o teto do rate limiter como resultado da Fase 1.

### 9.4 Regras de parada registradas (para a execução da Fase 1)

Parar imediatamente ao detectar **qualquer** sinal de perda de integridade:
qualquer 5xx inesperado; duplicação de `idempotency_key`; divergência wallet/ledger;
`reconciliation_issues > 0`; deadlocks; Postgres perto de 500 conexões; pool Hikari
saturado; aumento sustentado de timeouts; outbox crescendo sem capacidade de drenagem.
Entre estágios (50 → 100 → 150 vendas/s, 30-60s cada): pausa para verificar recuperação,
principalmente o outbox.

---

## 10. Fase 1 — Execução e resultados (2026-08-09)

> **Status:** `executada` — elevação temporária do rate limit autorizada pelo usuário,
> com restauração obrigatória ao final. Fase 2 **bloqueada** até análise dos resultados.

### 10.1 Procedimento executado

1. **Snapshot** dos valores originais do rate limit (20/120/300/120 por min, janela 60s) — verificados ao vivo.
2. **Elevação temporária** para 10M (4 variáveis, `--skip-deploys`) + redeploy `c065ff57` (SUCCESS, 2 réplicas).
3. **Validação pós-deploy:** readiness UP, conexões 201, probe de 301 vendas em 5s com **0×429** (confirma teto 10M aplicado), sale_ok 100%.
4. **Fase 1A/1B/1C** (50/100/150 vendas/s × 45s, pool 20 lojistas, webhook HMAC assinado) com monitor de conexões + checklist de integridade + pausa de recuperação entre estágios.
5. **Restauração obrigatória** dos valores originais + redeploy `4df0322d` (SUCCESS, 2 réplicas) — vars confirmadas 20/120/300/120.

### 10.2 Resultados por estágio (k6 local, 1 IP, `capacity-stages.js`)

| Estágio | Alvo | it/s real | % alvo | sale_ok | 5xx | http_req_failed | p50 | p95 (total) | p95 charge | p95 webhook | max | dropped |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1A — 50/s × 45s | 2.250 | 2.250 (41,76/s med) | 100% | 100% | **0,00%** | 0/4.520 | 216ms | 252ms | 263ms | 229ms | 769ms | 0 |
| 1B — 100/s × 45s | 4.500 | 4.500 (87,05/s med) | 100% | 100% | **0,00%** | 0/9.020 | 214ms | 267ms | 322ms | 234ms | 1,38s | 0 |
| 1C — 150/s × 45s | 6.750 | 6.724 (130,55/s med) | 100%* | 100% | **0,00%** | 0/13.468 | 214ms | 505ms | **1.004ms** | 293ms | 2,85s | 27 |

\* 27 `dropped_iterations` = k6 sem VUs suficientes sob latência (não é falha da app; mesmo
padrão documentado no F7 §2.1). VUs máximo: 1A=48, 1B=110, 1C=415 (preAllocated 200/300/400).

**Leitura:** 50 e 100 vendas/s são folgados (p95 ~250-270ms, estável). A **primeira
inflexão aparece em 150/s**: p95 do `charge` salta de 263→322→**1.004ms** e o p95 total vai
a 505ms, com max 2,85s — **sem nenhuma perda de integridade** (0 5xx, 0 falhas, 0 dups).
Conexões Postgres ficaram em **201-202/500** (pool 100×2) durante todos os estágios —
nenhum sinal de saturação do pool Hikari nem do Postgres.

### 10.3 Checklist de integridade (pós-cada-estágio e final)

| Verificação | 1A | 1B | 1C | Final (pós-restauração) |
|---|---|---|---|---|
| Conexões Postgres | 202 | 201 | 201 | **201/500** |
| Duplicações `idempotency_key` | 0 | 0 | 0 | **0** |
| `reconciliation_issues` | 0 | 0 | 0 | **0** |
| Divergência wallet × ledger | 0 | 0 | 0 | **0** |
| Deadlocks (`pg_stat_database`) | 0 | 0 | 0 | **0** |
| Outbox — recuperação na pausa | −1.200/60s | −1.200/60s | −1.200/60s | drenando (11.800 SENT/10min) |
| Readiness | — | — | — | **UP** |
| ERRORs no deploy de restauração | — | — | — | **0** |
| Rate limit restaurado | — | — | — | **20/120/300/120 ✓** |

**Outbox:** durante os estágios o PENDING cresce (criação > drenagem: 1C criou 6.750 eventos
vs drenagem ~20-30/s) — comportamento conhecido (F8 §6.2). Na pausa, **recupera sempre**
(−1.200 eventos/60s ≈ 20/s), sem retries nem FAILED. Backlog acumulado do F7/F8 segue
drenando (~11-16k SENT/10min).

### 10.4 Conclusão da Fase 1

- **Degradação começa entre 100 e 150 vendas/s** (p95 charge 322→1.004ms), manifestada
  como latência, **não** como falha — integridade preservada em todos os estágios.
- Com pool 100×2 o Postgres ficou folgado (201/500); o próximo gargalo continua sendo
  latência de `charge` sob contenção (provável fila de transação/commit), a investigar.
- **Fase 2 (explosão até quebrar) permanece BLOQUEADA** até a análise destes resultados.

---

## 11. Fase 2 — ABORTADA na 2A por sinal de parada (2026-08-09)

> **Status:** `abortada` na Fase 2A (200/s). Dispatcher do outbox travou na pausa de
> recuperação → regra de parada acionada. Rate limit restaurado; dispatcher desbloqueado
> e drenando. **Próximas fases bloqueadas até análise.**

### 11.1 Fase 2A — 200 vendas/s × 45s (único estágio executado)

| Métrica | Valor |
|---|---|
| it/s real | 8.869 vendas (197/s; 132 dropped = 1,5% — k6 sem VUs, não é falha da app) |
| sale_ok | **100,00%** (8.869/8.869) |
| 5xx | **0,00%** (0/0) |
| http_req_failed | 0/17.768 |
| Latência | p50 232ms · p95 total 771ms · **p95 charge 1.450ms** · max 3,28s |
| Conexões Postgres | 201-202/500 |
| Integridade pós-2A | dups 0 · reconciliation 0 · wallet×ledger 0 · deadlocks 0 |

### 11.2 Incidente: dispatcher do outbox TRAVOU (regra de parada)

**Sintoma:** na pausa pós-2A, o outbox **não drenou** (PENDING estático em 1.945.168;
SENT=0 em 2min) — quebrou o padrão de recuperação da Fase 1 (−1.200/60s).

**Diagnóstico (evidência no banco):**
- `shedlock.nyxcash-notification-dispatch`: `locked_at=07:11:49`, `lock_until=07:51:49`
  (+40min = `lockAtMostFor`) — **lock adquirido e nunca liberado** (uma execução travou).
- `job_runs`: última run `07:11:38` SUCCESS (3,5s) → **nenhuma execução depois**.
- `pg_stat_activity`: todas as conexões JDBC **idle** (sem query presa) → o travamento é
  **no thread da app**, não no banco. Causa provável: batch preso em chamadas HTTP a
  webhooks de lojista (timeout 10s × lote ≤ 240 — o lock foi dimensionado para isso, §6.2).
- **Relógio das instâncias ~3h à frente do UTC real** (evidente em todos os `locked_at`
  dos jobs): o `lock_until` escrito pela app fica "no futuro" para os containers novos,
  então **o redeploy NÃO desbloqueou** (novo container via lock até 07:51:49 ≈ 04:51 real).

**Remediação (aprovada pelo usuário):**
1. Rate limit **restaurado** (20/120/300/120) + redeploy `6a21faab` (SUCCESS) — produção
   segura, thread travada morta com o container antigo.
2. `UPDATE shedlock SET lock_until = now()-interval '1 minute' WHERE name='nyxcash-notification-dispatch'`
   → novo container assumiu o lock em ≤10s e **retomou a drenagem** (runs a cada ~10s
   SUCCESS; PENDING −1.000/30s ≈ 33/s; SENT voltando).

### 11.3 Checklist de integridade final (pós-remediação)

| Verificação | Resultado |
|---|---|
| Conexões Postgres | **201/500** |
| Duplicações `idempotency_key` | **0** |
| `reconciliation_issues` | **0** |
| Divergência wallet × ledger | **0** (APPROVED 1.072.183, PENDING 2.090 constante) |
| Deadlocks | **0** |
| Outbox | **drenando** (11 runs/2min, ~33/s, PENDING decrescente) |
| Rate limit | **restaurado 20/120/300/120 ✓** |
| Deploy ativo | `6a21faab` SUCCESS, 2 réplicas, readiness UP |

### 11.4 Lições e pendências

1. **ShedLock × clock skew:** o relógio ~3h adiantado das instâncias torna `lock_until`
   futuro e imune a redeploy. Mitigação operacional: verificar/limpar o lock antes de
   assumir que o redeploy desbloqueia. (Investigar o clock é tarefa de infra separada.)
2. **Causa raiz do travamento do batch** não confirmada (provável timeout HTTP em
   webhook de lojista no backlog F7/F8). A investigar antes de retomar qualquer fase de
   carga: quais merchants têm webhook e qual o comportamento do `HttpMerchantNotifier`
   sob fila longa.
3. **Fase 2 não completa:** 2B/2C/2D (300/500/750) **não executadas**. Teto de
   integridade não encontrado — só se sabe que 200/s passa limpo (p95 charge 1,45s) e que
   o dispatcher trava sob backlog+teste. **Próximas fases bloqueadas até análise.**

### 11.5 Etapa 1 — correção cirúrgica do dispatcher (aprovada e aplicada 2026-08-09)

Endereça a pendência 1 do §11.4 (ShedLock × travamento). Três ajustes defensivos, sem
mudança de lógica de negócio:

| Ajuste | Antes | Depois | Efeito |
|---|---|---|---|
| `lockAtMostFor` do dispatcher | PT40M | **PT5M** | Batch preso libera o lease em ≤5min; próximo ciclo (mesma ou outra instância) assume. Overlap entre ciclos é coberto por **at-least-once + idempotência do receptor** (`X-FoxCash-Event-Id`). Nenhuma instância segura o lock por 40min. |
| Guarda `max-batch-size` | `240 = 2400s/10s` (derivado do lock) | **Teto absoluto 240** (desacoplado do lock) | Produção (`NYX_NOTIFICATION_MAX_BATCH_SIZE=200`) continua bootando; o teto limita o pior caso da run (240 × 10s ≈ 40min) e o tamanho da query. |
| Timeout HTTP webhook | já existia (connect 5s / request 10s) | inalterado | Confirmado: `HttpMerchantNotifier` usa `HttpClient.connectTimeout(5s)` + `HttpRequest.timeout(10s)` — cada evento está limitado a ~10s. |

**Clock skew (pendência §11.4.1 — investigação):**
- O ShedLock **já usa `usingDbTime()`** (`SchedulingConfig`): aquisição/expiração de lock
  comparam contra o relógio do **banco**, não o da instância → o skew de ~3h das instâncias
  **não afeta** o takeover do lock. O "redeploy não desbloqueia" do incidente foi lease normal
  (lock_until no futuro até 07:51:49), não skew.
- Adicionado **`ClockSkewProbe`** (`SchedulingConfig`): loga no boot o delta `app_now × db_now`.
  Evidência para corrigir o relógio dos hosts (Railway) — correção do fuso é **infra**, não código.
- Ponto de atenção documentado: `NotificationDispatcherService` usa `Instant.now()` (app) no
  `findPendingDue(now)` — se o relógio da instância estiver adiantado, retries/backoff dispararam
  antes da hora (comparação app-vs-app cancela para timestamps escritos pela app; relevante só se
  houver timestamps escritos pelo banco).

**Validação:** `mvn test` → **1.106 testes, 0 falhas, 0 erros** (1.105 anteriores + 1 novo:
regressão `max-batch-size=200` de produção aceito). Pendências em aberto para a Etapa 2:
causa raiz do batch lento (webhooks de lojista).

### 11.6 Etapa 1 — deploy validado + causa raiz do "+3h" (2026-08-09)

Deploy `7e8a6e89` (push `b58069e`) validado em produção:

| Verificação | Resultado |
|---|---|
| Health / réplicas | UP, 2/2 no deployment `7e8a6e89` |
| Lock ShedLock do dispatcher | **PT5M ativo** (lease +5min confirmado no banco), rotacionando entre réplicas |
| Outbox | **drenando ~38/s** (PENDING 1.901.509 → 1.896.909 em ~2min; SENT subindo) |
| Duplicações `idempotency_key` | **0** |
| `reconciliation_issues` | **0** |
| Divergência wallet × ledger | **0** |
| Deadlocks / Conexões | **0** / 201 |

**Causa raiz do "+3h" encontrada (não é clock do container):** máquina = banco =
GitHub = UTC real (delta 0). As colunas `shedlock`/`job_runs`/`notification_outbox`
são `timestamp without time zone`, e a conversão naive nesta instância Postgres
adiciona +3h (`now()`=04:49Z, `now()::timestamp`=07:49). O ShedLock `usingDbTime()`
compara no mesmo domínio naive → **locking autoconsistente** (leases corretos em
tempo absoluto). O `ClockSkewProbe` mede instantes absolutos (app vs db) e, por
isso, NÃO detecta esse offset naive — limitação documentada.

**Incidente pós-deploy (transição):** o lock herdado do deployment antigo
(`6a21faab`, REMOVED) foi adquirido por um container morto às 04:44Z com lease
PT40M → bloqueou os containers novos até ~05:24Z (outbox estagnado). **Remediação
aprovada:** `UPDATE shedlock SET lock_until = now()-interval '1 minute' WHERE
name='nyxcash-notification-dispatch'` → container novo assumiu em ≤10s com lease
PT5M e retomou a drenagem. Confirma o valor do fix: qualquer lock preso agora
expira em ≤5min sem intervenção.

### 11.7 Causa raiz do hang do dispatcher + estabilização (2026-08-09)

**Investigação da pendência §11.4.2 (webhooks de lojista) — REFUTADA:** a tabela
`merchant_webhooks` está **vazia** (0 lojistas com webhook; backlog 100%
CHARGE_CREATED/SALE_PAID) → toda entrega é fallback log (sem HTTP). O travamento
NÃO era timeout de webhook.

**Causa raiz encontrada (2 mecanismos):**
1. **Seq Scan no outbox** (corrigido V45, deploy `411c7ff`): `findPendingDue` com
   predicado `IS NULL OR <= now` não usava o índice e varria ~2M linhas a cada
   poll (10s). Sob carga o scan degradava. Fix: query dividida em 2 ramos
   indexáveis + índice parcial `ix_notification_outbox_pending_created` (FIFO) —
   plano caiu de **Seq Scan (custo 141k) → Index Scan (custo 15)**.
2. **Exporter OTLP bloqueando o span export** (corrigido deploy `9334018`): sem
   collector, o Spring tentava `localhost:4318` a cada ~20-40s (spam de erro) e,
   com a fila do batch span processor cheia (`exportWhenQueueFull` default
   bloqueia o thread que finaliza o span), uma run do dispatcher ficava presa em
   I/O logo após cada deploy. Fix: `TracingConfig` com SpanExporter **no-op** sem
   `NYX_OTLP_ENDPOINT` + exclusão do `OtlpAutoConfiguration` (traceId/spanId
   seguem no MDC — Spec 57 preservado).

**Defesas adicionais (deploys `f17f590`):** watchdog de 2min na run do dispatcher
(loga o stack da thread presa e libera o scheduler — `fixedDelay` nunca mais
para de vez) + lock PT5M (JÁ provado: o hang pós-deploy de 05:16 foi absorvido
pela expiração do lease em 5min sem intervenção).

**Estado final verificado (deploy `86c9d98e`):**

| Verificação | Resultado |
|---|---|
| Drenagem | **~23-27/s contínua** (PENDING decrescente, last_sent fresco a cada run) |
| Lock ShedLock | PT5M rotacionando entre as 2 réplicas, releases entre runs |
| Erros OTLP | **0** desde o deploy (era 1 a cada ~20-40s) |
| DISPATCH_WATCHDOG | **0** disparos (sem hangs) |
| traceId/spanId MDC | preservados |
| Testes | 1.109 (0 falhas) |

### 11.8 Etapa 2 — Fase 2 completa: 200→300→400→500/s (2026-08-09)

Retomada com rate limit ELEVADO (10M, `--skip-deploys` + redeploy) e o dispatcher
já estabilizado (§11.7). `capacity-stages.js`, k6 local 1 IP, webhook HMAC.

| Fase | Alvo | Vendas reais | sale_ok | 5xx | p95 charge | Gate pausa pós-carga |
|---|---|---|---|---|---|---|
| 2A | 200/s | 8.771 | **100,00%** | 0 | 975ms | ✅ drenando ~25-28/s |
| 2B | 300/s | 12.839 | **100,00%** | 0 | 2.066ms | ✅ drenando ~30/s |
| 2C | 400/s | 16.812 (rev1) | 99,66% | **0** | 1.712ms | ✅ drenando ~33/s |
| 2D | 500/s | 18.706 | 99,50% | **0** | 6.533ms | ✅ drenando ~33/s |

**Interpretação dos <100% em 2C/2D:** `sale_5xx=0` e integridade ZERO (dups 0,
wallet×ledger 0, reconciliation 0, deadlocks 0, `pix_transactions` PENDING
constante em 2.090 — sem cap/409). As falhas residuais (2C: 0,16% com
`MAX_VUS=1000`; 2D: 0,25%) são **timeouts de conexão do edge Railway por IP do
k6** (limite documentado na F7 ~1.500 VUs/IP) — NÃO falhas da app. A 2C sem
`MAX_VUS` o k6 escalou para ~1.400 VUs e as falhas subiram a 1,91% (confirma a
causa de gerador, não de app).

**Conclusões da Etapa 2:**
1. **A pausa pós-carga FUNCIONA** — o gate que abriu a 2A original (outbox estático)
   agora passa em todas as fases: o outbox drena continuamente (~25-33/s) mesmo
   com a carga; o dispatcher NÃO travou em nenhuma fase.
2. **Teto de integridade NÃO encontrado** — nenhuma fase gerou divergência
   (integridade perfeita até 500/s). O gargalo é **latência**: charge p95 sobe de
   975ms (200/s) para 6,5s (500/s) — a app satura em latência antes de quebrar
   consistência.
3. **Gerador 1 IP é o limitante acima de ~400/s** (edge). Para 600→750/s (plano
   original 2D), usar **geradores distribuídos** (loadgen Railway, `grafana/k6`).

**Restauração obrigatória aplicada:** rate limit de volta a **20/120/300/120**
(deploy `cbc85f3a`, SUCCESS). Outbox pós-fases: backlog acumulado ~1,98M
(1.855.383 → ~1,98M) **drenando ativamente** com o rate limit restaurado.
Health UP, 2/2 réplicas, integridade 0/0/0/0.

### 11.9 Etapa 2 — Fase 2B: geradores distribuídos 600→750/s (2026-08-09)

Retomada pós-crash (a máquina local travou durante a fase; carga foi retomada e
concluída no mesmo dia). Objetivo: contornar o limite do edge por-IP que travou a
Etapa 2 acima de ~400/s (gerador local = 1 IP). Infra: serviço Railway `loadgen`
(`grafana/k6` + `capacity-stages.js` bakeado via `railway up ./loadgen
--path-as-root`), **rede privada** `BASE_URL=http://nyxcash.railway.internal:8080`
(bypassa o edge público), rate limit elevado (10M) e HTTPS relaxado durante o teste.

**Achados de implementação (o loadgen não gerava tráfego — 3 causas corrigidas):**
1. **Start command do serviço** era `sh -c "sleep infinity"` (criado como imagem,
   sem script) → o container dormia e o k6 nunca rodava. Correção via API
   (`serviceInstanceUpdate input:{startCommand:""}`) + redeploy via `railway up`
   (`railway deployment redeploy` reusa o config do deployment antigo — não aplica
   a mudança; só `railway up` aplica o config atual).
2. **Porta do app na rede privada**: `nyxcash.railway.internal:80` → `connection
   refused` (o app escuta em `${PORT:8080}`). Fix: `BASE_URL=...:8080`.
3. **`setup()` quebrava em falha de conexão**: `res.body.slice(0,200)` com body
   null → TypeError mascarava o diagnóstico real (connection refused). Fix:
   `(res.body || res.error || '')`.

**Execução:** 2 fases com N réplicas × STAGE (cada réplica roda o script inteiro,
idempotencyKey única por instância via sufixo aleatório no setup).

| Fase | Config | Vendas | sale_ok | 5xx | p95 charge | p95 webhook | dropped (VU cap) |
|---|---|---|---|---|---|---|---|
| 600/s | 1 réplica × STAGE=300 (calibração) | 12.565 / 45s ≈ **280/s** | 100,00% | **0** | 1.060ms | 187ms | 935 |
| 600/s | 2 réplicas × STAGE=300 (run limpa) | 21.790 / 46s ≈ **474/s** | 100,00% | **0** | 5.567ms | 236ms | 5.211 |
| 750/s | 3 réplicas × STAGE=250 | 28.854 / ~60s ≈ **480/s** | 100,00% | **0** | 5.796ms | 176ms | ~1.630/réplica |

(Eventos de scale `numReplicas` 1→2 geraram 2 runs extras interrompidas — 7.850 +
7.710 vendas, também 100% OK/0 5xx — incluídas na contagem total de integridade.)

**Conclusões da Fase 2B (distribuída):**
1. **Teto real sustentado da app (2 réplicas): ~470-480 vendas/s agregadas** — a
   chegada de 600/s e 750/s **não** aumenta o throughput: a app satura ~480/s com
   degradação de latência (charge p95 ~5,5s, max ~8,8s) e o gerador estoura o
   VU cap (dropped). O gargalo é o caminho do charge (auth + idempotência + DB +
   PSP na mesma requisição) — as réplicas do TrevoPay processam ~240/s cada antes
   de degradar.
2. **Integridade sob carga (zona sagrada): 0 duplicações** `idempotency_key`
   (49.915 keys distintas no janelão, 0 dups), **0 divergência wallet×ledger**
   (2.860 merchants), **0 reconciliation issues**, **0 deadlocks**, 0 5xx em
   TODAS as fases — mesmo com VU starvation do gerador.
3. **Webhook path é folgado**: p95 webhook ~176-236ms mesmo a 480/s (o lock do
   webhook serializa por transação, não estoura).
4. **Outbox continua sendo o limite assíncrono**: +~157k eventos gerados nas fases
   (~79k vendas × 2), backlog 1,94M → 2,03M, drenagem ~25-33/s (dispatcher ativo,
   `last_sent` fresco; escoa em ~21-25h sem perda — sem `FAILED`, sem retries).
5. **Número de capacidade reportável (honesto, medido): ~480 vendas/s sustentadas
   com 2 réplicas, p95 charge ~5,5s, 0% 5xx, 0 duplicações, 0 perda de
   ledger/outbox.** A meta de 5.000 vendas/s NÃO é alcançada com esta
   configuração — o relatório final (task 30) deve usar este número medido e
   listar os gargalos: (a) caminho do charge (por venda: ~5-6 SELECTs de auth +
   idempotência + write + PSP), (b) latência degrada acima de ~240/s/réplica,
   (c) outbox não acompanha carga acima de ~30 eventos/s de drenagem.

**Restauração obrigatória aplicada:** HTTPS de volta a `true` (HTTP→301) + rate
limit **20/120/300/120** (deploy `b7f2906f`, SUCCESS; probe ao vivo: 20×400 +
2×429 no bucket público). Pool Hikari 100 e Tomcat threads 1000 mantidos
(varáveis de capacidade, §6.1). **Serviço `loadgen` DELETADO** (fim do teste —
nenhum gerador ativo em produção). Integridade pós-restauração: 0/0/0/0.

### 11.10 Spec 65 — baseline (antes) e plano da prova (depois)

Registra o baseline **antes** da Spec 65 (do §11.9) e o que a campanha
consolidada (62 F8/F9 + 65 F4) vai medir **depois** — regra de ouro da Spec 62:
o número reportado é o medido.

**Antes (baseline §11.9 / `pg_stat_statements`):**

| Métrica | Antes |
|---|---|
| Vendas/s sustentadas (2 réplicas) | ~474-480/s (600/s e 750/s não sobem: teto de recurso compartilhado) |
| p95 charge | ~5,5s (max ~8,8s) |
| Queries de auth (por venda) | ~5-6 SELECTs + 1 UPDATE (`update merchant_api_keys` 98.710/41ms; `select merchants` 98.710/23ms — top-2) |
| Drenagem do outbox | ~20-33 eventos/s (batch 200, poll 10s → pós-incidente 30s, 1 lote/lock) |
| Zona sagrada | 0/0/0/0 (dups, wallet×ledger, reconciliation, deadlocks) |

**Mudanças da Spec 65 (Fase 1 + Fase 3, deploy pós-campanha):** auth
consolidado (-1 SELECT/venda; `AuthenticatedMerchantResolver`) + throttle do
`last_used_at` (~1 UPDATE/min/chave) + outbox com knobs meio-termo (batch
default 500/teto 500, poll PT10S, `max-batches-per-run` 10) e loop de lotes por
lock. **Fase 2 (cache curto-TTL) GATED** — só se o "depois" abaixo mostrar as
leituras restantes como gargalo.

**Depois (campanha 62 F8/F9 + 65 F4 — 600/s (2×300) e 750/s (3×250), runbook
`load-tests/k6/distributed-runbook.md`):** preencher com as MESMAS métricas
acima (vendas/s sustentadas, p95 charge, queries/s no `pg_stat_statements` —
queries de auth saindo do topo, drenagem outbox SENT/s) + zona sagrada 0/0/0/0.
Decisão PgBouncer/réplicas (ADR-013) registrada aqui.

### 11.11 Campanha consolidada — 65 F4 + 62 F8 + 62 F9 (executada 2026-08-10)

Resultados **medidos** da campanha (regra de ouro: o número reportado é o
medido). Metodologia: `load-tests/k6/distributed-runbook.md` — loadgen Railway
(rede privada `nyxcash.railway.internal:8080`), 1 venda = cobrança +
webhook PAID assinado, rate limit ELEVADO (10M) durante capacidade (não
mascara o teto), ATIVO (20/120/300/120) durante a explosão.

#### 65 F4 — re-benchmark 600/s e 750/s (pós-Spec 65)

| Métrica | Antes (§11.9) | Depois (65 F4) |
|---|---|---|
| Vendas/s (2 réplicas) | ~474-480/s | **600/s sustentado** (2×300), 0 5xx, p95 charge ~119-133ms |
| p95 charge | ~5,5s (max ~8,8s) | **~119-133ms** (600/s) / ~925-1.035ms (750/s — inflexão) |
| Vendas/s (3 réplicas, 750/s chegada) | ~480/s (não subia) | **744/s sustentado**, 0 5xx, p95 ~925-1.035ms |
| Zona sagrada | 0/0/0/0 | **0/0/0/0** (dups, wallet×ledger, reconciliation, deadlocks) |
| Outbox | ~20-33 eventos/s | **~135-140 eventos/s** (medido pós-campanha; 0 FAILED) |

**Conclusão 65 F4:** a otimização da Spec 65 (auth consolidado -1 SELECT/venda
+ throttle `last_used_at` + outbox com loop de lotes) **eliminou o gargalo
compartilhado do charge**: 600/s deixou de degradar (p95 ~5,5s → ~120ms) e o
teto subiu para ~600/s com 2 réplicas. A inflexão de latência fica entre
600/s e 750/s. **Fase 2 (cache curto-TTL) DISPENSADA por evidência** — as
leituras de auth não são mais o gargalo (regra de ouro). Decisão registrada.

#### 62 F8 — explosão 5k/s (rate limit ATIVO)

- 5k/s de chegada (3 geradores × STAGE=1667): **~100% 429/backpressure** (rate
  limit transacional Spec 58 cortando a chegada de forma plana), **0,00% 5xx**,
  p95 charge 105,6ms / webhook 159,4ms, 49.898 iterações.
- **Quebra bonita provada**: degradação via 429 ANTES de Postgres/pool/JVM;
  gate pós-carga dups=0 / reconciliation_open=0 / deadlocks=0 / outbox 0
  FAILED. Estágios 7.5k→20k PULADOS (mesmo resultado, decisão do usuário).
- Evidência: `load-tests/k6/results/fase4-f8-explosao-5k.json`.

#### 62 F9 — scale-out 2→4→8→16 réplicas (rate limit elevado)

| Réplicas | Chegada | Vendas/s sustentadas | p95 charge | sale_ok | 5xx | Nota |
|---|---|---|---|---|---|---|
| 2 | 600/s | ~600/s (65-F4) | ~120ms | 100% | 0 | baseline otimizado |
| 4 | 1.300/s (4×325) | **~1.246/s** | ~1,14-1,17s | 100% | 0 | scale-out ~linear (2× réplicas → ~2× throughput) |
| 8 | 2.600/s (8×325) | **~1.554/s** | ~4,5-5,8s | 100% | 0 | NÃO linear (+25%) — **Postgres max_connections=500 esgotado** (800 desejadas) |
| 16 | 5.200/s (16×325) | **DEGRADA** | ~10-19s | 94-97% | 0 | Postgres 500/500 saturado; setup do loadgen CRASHED |

**Gargalo central identificado e medido:** o **limite de conexões do Postgres
(max_connections=500)** — com pool Hikari 100/réplica, 8 réplicas desejam 800
conexões (`too many clients already` ao conectar). O scale-out é **útil até
~4-8 réplicas (~1.250-1.550/s)** e **degrada além disso** (16 réplicas piora a
latência e o sale_ok sem ganho de throughput). A zona sagrada permaneceu
**0/0/0/0 em todos os estágios** (mesmo com o banco saturado) — integridade
financeira intacta.

**Decisão PgBouncer/réplicas (ADR-013):** o gargalo de conexões é REAL e foi
medido. Duas correções candidatas com evidência: (a) **reduzir o pool Hikari
por réplica ao escalar** (ex.: pool 60 × 8 = 480 < 500) — mais réplicas com
pool menor mantém o total sob o limite, sem infra nova; (b) **PgBouncer**
(transaction pooling) para desacoplar conexões da app do limite do Postgres.
**Decisão: ADR-013 é atualizada com o achado; a correção (a) é a recomendada
para scale-out imediato** (sem infra nova, baixo risco) e o PgBouncer fica
como candidato se o scale-out além de ~8 réplicas for necessário. Evidência em
`load-tests/k6/results/fase4-f9-estagio4-4replicas.json`,
`fase4-f9-estagio8-8replicas.json`, `fase4-f9-estagio16-16replicas.json`.

**Restauração obrigatória aplicada (2026-08-10):** rate limit de volta a
**20/120/300/120** + HTTPS `true` (verificado: 429 no 22º request anônimo;
health 200 UP; app 2/2 réplicas) + **serviço loadgen DELETADO** (nenhum
gerador ativo). Gate pós-restauração: **0/0/0/0**. Outbox drenando a
~135-140/s (backlog ~737k PENDING, 0 FAILED — escoa em horas, sem perda).
