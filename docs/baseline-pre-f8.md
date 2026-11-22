# Baseline de integridade pré-F8 — TrevoPay (Spec 62)

> **Propósito:** registrar o estado do banco e da aplicação ANTES de qualquer teste de
> explosão (F8), conforme diretriz do usuário (2026-08-08). Coleta 100% read-only —
> nenhuma escrita, nenhum comando destrutivo, nenhuma geração de carga.
> **Janela de coleta:** 2026-08-08 23:24–23:25 local (UTC-3) = 02:24–02:25 UTC 09/08.
> **Script:** `load-tests/baseline-pre-teste.cjs` (túnel `railway connect -s Postgres -p 15432`).
> **Ambiente:** Railway `production` (`foxcash-prod`), 2 réplicas TrevoPay, Postgres 18.4.

---

## 1. Verificação de bloqueio (gate) — todas OK

| Verificação | Resultado | Evidência |
|---|---|---|
| Postgres saudável e estável | ✅ OK | Online, primário (`pg_is_in_recovery=false`), PostgreSQL 18.4 |
| App saudável nas 2 réplicas | ✅ OK | `● Online 2/2`, `/actuator/health/readiness` = UP, deploy `14a0ce3b` SUCCESS 23:18 |
| Erros 5xx | ✅ 0 | Nenhum padrão de 5xx/ERROR nos logs (só ruído conhecido do OTel exporter → `localhost:4318`) |
| Restarts | ✅ 0 | Nenhum boot repetido / OOM / shutdown nos logs recentes |
| Locks problemáticos | ✅ 0 | `pg_locks NOT granted` = 0; deadlocks acumulados = 0 |

> **⚠️ Exceção (não bloqueia saúde, mas bloqueia F8):** rate limit de produção **ainda
> elevado** (10.000.000) — ver §2.2. F8 exige rate limit **ATIVO** (tetos originais).

---

## 2. Estado atual do banco (medido)

### 2.1 Conexões e saúde PostgreSQL

| Métrica | Valor |
|---|---|
| Conexões totais | **402** (máx 500) — 401 idle, 1 active, 0 idle-in-txn |
| Locks em espera (`NOT granted`) | **0** |
| Deadlocks acumulados (uptime) | **0** |
| `xact_commit` / `xact_rollback` | 12.253.910 / 2 |
| Cache hit | 96,94% |
| Tamanho do banco | **3113 MB** |
| WAL (diretório) | 63 arquivos, **1008 MB** (`wal_level=replica`, `max_wal_size=1024MB`, `max_connections=500`) |
| `shared_buffers` | 128 MB (16384×8kB) |
| `pg_current_wal_lsn()` | 4/5DCBB468; lag insert→current = 0,0 MB |

### 2.2 Configuração de produção (rate limit / capacidade) — **DISCREPÂNCIA**

| Variável | Valor atual no Railway | Esperado (revert do commit `95fcbb3`) |
|---|---|---|
| `NYXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS` | **10.000.000** | 20/min |
| `NYXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS` | **10.000.000** | 120/min |
| `NYXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS` | **10.000.000** | 300/min |
| `NYXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | **10.000.000** | 120/min |
| `NYX_RATE_LIMIT_STORE` | redis | redis |
| `NYX_DB_POOL_SIZE` | 200 | 200 (mantido, intencional) |
| `SERVER_TOMCAT_THREADS_MAX` | 1000 | 1000 (mantido) |
| `NYX_TOMCAT_MAX_CONNECTIONS` / `ACCEPT_COUNT` | 10000 / 1000 | mantidos |

> **Leitura:** o commit `95fcbb3` documentou a restauração dos tetos, mas **as variáveis
> atuais no Railway continuam em 10M**. O deploy 14:00:14a0ce3b (23:18) rodou com os tetos
> elevados. Produção está, neste momento, **sem rate limiting efetivo** (anti-DoS
> desativado). Restaurar ANTES de qualquer F8.

### 2.3 Transações / vendas

| Métrica | Valor |
|---|---|
| `pix_transactions` total | **1.048.877** |
| Por status | APPROVED **1.046.868** · PENDING **2.009** (~0,19%, sem webhook) |
| Soma de valores (APPROVED) | R$ 20.832.285,80 |
| `idempotency_key` distinct | **1.048.877** (= total) |
| `idempotency_key` NULL | 0 |
| **Duplicações `idempotency_key`** | **0** (zero chaves com count > 1) |
| Janela temporal | criadas entre 2026-08-08 ~22:22 e ~01:50 local (fim da F7) — sem carga nova desde então |

### 2.4 Integridade zona sagrada (wallet vs ledger)

Regra da Spec 30/27: `merchants.wallet_balance == balance_after` da **última** entry do
`wallet_ledger` por lojista (sem entry → consistente por definição).

| Métrica | Valor |
|---|---|
| Lojistas totais | 2.312 |
| Com wallet | 2.312 |
| Com entry de ledger | 2.312 |
| **Divergências (mismatches)** | **0** |
| `reconciliation_issues` em aberto | **0** |

### 2.5 Outbox de notificações

| Métrica | Valor |
|---|---|
| PENDING | **2.057.256** |
| SENT | 38.171 |
| Idade média PENDING | ~2,1 h (mais antigo ~22:51 local; mais novo ~01:50 local — fim da F7) |
| PENDING com `attempts=0` | 2.057.256 (100%) |
| **Taxa de drenagem** | ~100 eventos/min (**~6.000/hora**) — medido: PENDING 2.057.406 → 2.057.256 em 90s (−150) |
| SENT última 1h | 8.367 |
| SENT última 24h | 38.119 |
| Nova carga (txn/outbox por minuto) | **0** — benchmark parado, loadgen não gerou tráfego |
| `job=nyxcash-notification-dispatch` | SUCCESS a cada ~30s (50 eventos/lote, duration ~234ms) |

> **Leitura:** o outbox está **estável e escorrendo** (backlog decrescente, sem carga nova).
> Porém a taxa de drenagem (~6k/h) é ~70× menor que a taxa de criação da F7 (~441k/h no
> pico). Drenar o backlog atual (2,06M) levaria ~14 dias. **Não iniciar F8 sem tratar isso**
> ou sem aceitar crescimento explosivo do backlog.

### 2.6 pg_stat_statements (top queries por tempo total — contexto do gargalo)

| Query (resumo) | Calls | Total (s) | Mean (ms) |
|---|---|---|---|
| SELECT lojista por id (auth) | 992.625 | 25.918 | 26,11 |
| UPDATE `merchant_api_keys` (last_used_at) | 1.007.972 | 15.922 | 15,80 |
| UPDATE `pix_transactions` (webhook PAID) | 1.986.895 | 235 | 0,12 |
| INSERT `notification_outbox` | 1.986.895 | 147 | 0,07 |
| INSERT `pix_transactions` | 994.270 | 125 | 0,13 |
| INSERT `wallet_ledger` | 992.798 | 102 | 0,10 |
| INSERT `platform_activity_log` | 1.989.137 | 87 | 0,04 |
| COUNT pendentes por lojista | 994.343 | 80 | 0,08 |

---

## 3. Serviço `loadgen` (criado durante a tentativa)

| Atributo | Valor |
|---|---|
| Imagem | `grafana/k6:latest` |
| Status | **● Completed (parado — não está executando)** |
| Domínio público | **nenhum** (apenas `loadgen.railway.internal`) |
| Variáveis | `RAILWAY_SERVICE_NYXCASH_URL=nyxcash-production.up.railway.app` |
| Logs | Apenas help text do k6 (container iniciou sem script) → **não gerou tráfego** |
| Deployments | 23:10 (removido), 23:15 (removido), 23:16 SUCCESS/Completed |

> Confirmado: o loadgen **nunca enviou requisições** (0 transações/0 outbox novos por minuto
> desde o fim da F7). Manter parado conforme diretriz.

---

## 4. Linha do tempo da tentativa (2026-08-08, horário local UTC-3)

| Horário | Evento |
|---|---|
| 22:57 | commit `e7c9d99` — relatório F7 consolidado |
| 22:59 | commit `95fcbb3` — revert do rate limit documentado ("redeploy aplicado") |
| 23:10–23:16 | serviço `loadgen` criado (k6) — 3 deployments, nenhum tráfego |
| 23:18 | deploy TrevoPay `14a0ce3b` SUCCESS — **rodou com rate limit 10M** |
| 23:24–23:25 | coleta deste baseline (read-only) |

**Conclusão da auditoria da tentativa:** nenhuma alteração de código (working tree limpo),
nenhuma escrita no banco, nenhuma geração de carga efetiva. As únicas alterações foram
infra-side no Railway: criação do serviço `loadgen` (parado) e a **não-restauração efetiva
do rate limit** (10M ainda ativos).

---

## 5. Ações aprovadas e aplicadas após o baseline (2026-08-08 23:30–23:40 local)

| Ação | Status | Evidência |
|---|---|---|
| **Rate limit restaurado** (20/120/300/120 por min) | ✅ Aplicado | variáveis confirmadas no serviço; deploy `80ddd666` (23:32) → depois `448b015e` (23:39) |
| **Outbox: lote 50 → 200** (`NYX_NOTIFICATION_MAX_BATCH_SIZE=200`) | ✅ Aplicado | logs `Dispatcher de notificacoes: 200 evento(s) entregue(s)` |
| **Outbox: poll 30s → 10s** (`NYX_NOTIFICATION_POLL_INTERVAL=PT10S`) | ✅ Aplicado | runs alternadas entre as 2 réplicas (ShedLock) |
| **Invariância ShedLock** (lock PT10M → **PT40M** + guarda fail-fast `batch ≤ 240` = 2400s/10s) | ✅ Aplicado | `NotificationProperties.validate()` + 3 testes novos |
| Suíte `mvn test` | ✅ **1105/0/0** | inclui `NotificationPropertiesTest` (10 testes) |
| Commit | ✅ `66954c2` (pushed `main`) | auto-deploy Railway `448b015e` SUCCESS |

### 5.1 Nova taxa de drenagem do outbox (medida pós-mudança)

| Métrica | Antes | Depois |
|---|---|---|
| Lote por run | 50 | **200** |
| Poll | 30s | **10s** |
| **Drenagem medida** | ~6.000/h | **~136.000/h (≈22×)** |
| Medição | PENDING 2.057.406 → 2.057.256 em 90s (−150) | PENDING 2.054.756 → 2.047.956 em 180s (−6.800) |
| Retries (`attempts>0` PENDING) | 0 | **0** (entrega limpa, sem reentrega) |
| Nova carga | 0 | **0** (0 txn/0 outbox por minuto) |
| Backlog atual | 2.057.256 | **2.047.956** (~15h para drenar a 136k/h vs ~14 dias antes) |

> **Leitura:** a drenagem ainda é ~3× menor que a criação da F7 no pico (~441k/h), então o
> backlog CRESCERÁ durante um F8 pesado — mas a recuperação pós-teste caiu de ~14 dias para
> ~15 horas. A invariância de não-duplicação (ShedLock + guarda fail-fast) está preservada.

---

## 6. Fase 0 — smoke F8 executado (aprovado pelo usuário, 2026-08-08 23:48–23:49)

**Gerador:** k6 local v2.1.0 (1 IP), `capacity-stages.js`, STAGE=10 (10 vendas/s), DURATION=20s,
POOL=10, MAX_VUS=200, webhook PAID assinado HMAC-SHA256. **Rate limit ATIVO** (20/120/300/120).
Resultado bruto: `load-tests/k6/results/f8-smoke.json`.

| Métrica | Resultado |
|---|---|
| Iterações (vendas) | **201** (10.00 iters/s alvo = 100%) |
| Charges `POST /api/v1/nyx/pix/cob` | **201× 201** (todas criadas) |
| Webhook `POST /webhooks/pix` | **120× 204** + **81× 429** (rate limiter por IP: teto 120/min atingido — esperado) |
| **5xx** | **0,00%** (threshold `sale_5xx rate<0.0001` passou) |
| Latência charge | p95 **303ms** (max 599ms) |
| Latência webhook | p95 **250ms** |
| VUs usados | máx 8 |

### 6.1 Integridade pós-smoke (zona sagrada)

| Verificação | Resultado |
|---|---|
| Duplicações `idempotency_key` | **0** |
| Wallet vs ledger (mismatches) | **0** |
| `reconciliation_issues` OPEN | **0** |
| Transações novas | 201 → 120 **APPROVED** + 81 **PENDING** (webhook 429 → sem PAID; estado normal, sem duplicação) |
| Outbox novos | 321 (201 CHARGE_CREATED + 120 SALE_PAID) |
| 5xx nos logs da app (janela 23:48–23:49) | **0** |

> **Leitura do smoke:** o caminho completo funciona com tetos atuais (charge 201 + webhook 204
> com HMAC correto) e o rate limit está **ativo e limpo** (429, não 5xx) — exatamente a
> premissa do F8 ("quebra bonita"). O webhook 429 a ~10/s é o bucket de 120/min/IP fazendo
> backpressure; charges ficam PENDING (sem duplicação) até o webhook chegar ou expirar.
> **Drenagem sustentada pós-smoke:** ~100k/h (PENDING −3.297 em 120s). Nenhuma carga nova
> após o smoke (txn_1m=0).
