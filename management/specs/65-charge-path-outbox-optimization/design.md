# Spec 65 — Design: otimização do caminho do charge (auth) e drenagem do outbox

## Contexto

- **Baseline medido** (Spec 62, F7/Fase 2B + `pg_stat_statements`):
  - Tetos: 1 gerador ~280/s · 2 geradores ~474/s · 3 geradores ~480/s → o teto
    é de **recurso compartilhado** (mais réplicas de gerador não sobem; o
    Postgres/caminho do charge satura ~480/s).
  - Queries mais caras (produção): `update merchant_api_keys` (98.710, 41 ms)
    e `select merchants` (98.710, 23 ms).
  - `capacity-model.md` F1: auth = 5-6 SELECTs/venda + reload no service.
  - Outbox: ~20-33 eventos/s (batch 200, poll 10s, 1 lote por lock).
- **Fluxo de auth atual** (`ApiKeyAuthFilter.authenticateWithManagedKey`):
  1. `merchantApiKeyRepository.findByKeyHash(hash)` → SELECT (1);
  2. `merchantRepository.findById(merchantId)` → SELECT (2);
  3. `merchantApiKeyRepository.recordUse(id, now)` → **UPDATE** `last_used_at`
     (3 — o hot spot de escrita, 41 ms de média);
  4. o service do charge recarrega o lojista (SELECT adicional).
  Por venda (charge + webhook = 2 requests): ~6-8 SELECTs + 2 UPDATEs de auth.

## Decisões

### Fase 1 — consolidação de leituras + throttle de escrita (sem cache)

1. **Eliminar o reload do service** — **implementado (2026-08-09):**
   `AuthenticatedMerchantResolver` expõe o `Merchant` que o filtro de auth já
   carregou (atributo do `HttpServletRequest`; lido via `RequestContextHolder`,
   mesmo padrão do `PlatformActivityRecorder`). Os filtros que populam:
   `ApiKeyAuthFilter` (chave gerenciável + fallback legado) e
   `MerchantPortalAuthFilter` (portal). `CreatePixChargeService` reusa a MESMA
   entidade (fallback ao repositório fora de request — comportamento idêntico).
   O webhook (`findByIdForUpdate`) NÃO muda: é o lock pessimista do wallet
   (zona sagrada — integridade). Não muda contrato; muda o número de queries
   (auth por request: 3 SELECTs → 2).
2. **Throttle do `recordUse` (`last_used_at`)** — **implementado (2026-08-09):**
   o UPDATE roda no máximo **1×/intervalo por chave**
   (`foxcash.security.api-key.record-use-interval`, default 60s —
   `ApiKeySecurityProperties`). O throttle é um `ConcurrentHashMap<UUID, Instant>`
   no filtro (último UPDATE por chave, atômico por chave via `compute`),
   TTL-bounded (podado quando passa de 10.000 entradas). **Por instância** (≤ N
   writes/min/chave no pior caso com N réplicas — inventariado abaixo, Spec 61).
   O "último uso" do portal (Spec 24) mantém granularidade de minutos —
   aceitável e documentado. Este é o maior ganho isolado (mata o UPDATE de
   41 ms em cada request).
3. **Medir de novo** antes de decidir cache: re-benchmark (critério 5) +
   `pg_stat_statements` pós-Fase 1 — pendente (Fase 4).

### Fase 2 — cache curto-TTL (GATED por evidência da Fase 1)

Só se o re-benchmark mostrar que as **leituras** restantes (2 SELECTs/request)
ainda são o gargalo:

- `ApiKeyResolutionCache` (per-instância, `ConcurrentHashMap` + `Clock`):
  `keyHash → {merchantId, scopes, active}` com **TTL curto (30-60s)** e
  **tamanho máx** (ex.: 10.000 entradas, eviction LRU/TTL) — inventariado na
  tabela de estado da Spec 61. **Estado em memória adicional da Fase 1:** o mapa
  do throttle do `recordUse` (último UPDATE por chave) — também per-instância,
  TTL-bounded e sem vazamento (tamanho máx); ambos entram no inventário do
  design/auditoria (Spec 61) — comportamento com N instâncias: throttle por
  instância (≤ N writes/min/chave no pior caso) e cache por instância (TTL curto
  é o backstop; invalidação é a garantia).
- **Segurança (inegociável)**:
  - **Revogação/regeneração imediata** (Spec 24): `revoke`/`regenerate`
    chamam `cache.invalidate(keyHash)` (o service de gestão de chaves é o ponto
    único de mutação) — 401 imediato;
  - **Status do lojista sempre re-lido por request** (Spec 12/16): o cache
    guarda a resolução da CHAVE (imutável entre revogações); o filtro mantém o
    `findById` do lojista **fora** do cache (suspend/release — Spec 16 — nunca
    fica atrasado). Alternativa aceitável: cache do lojista com invalidação no
    `MerchantStatusControlService` (suspend/release) — escolher a mais simples
    na implementação, com teste de 401/403 imediato.
  - Escopos preservados; comportamento idêntico para chave inexistente
    (sem oráculo).

### 3. Outbox — tuning do dispatcher (Specs 35/53/54) — **implementado (2026-08-09)**

Meio-termo pós-incidente (Fase 2A — docs §11): o teto absoluto do lote sobe de
240 para **500** (fail-fast acima), o poll default desce de 30s para **10s** e o
loop de lotes por lock é o ganho estrutural (mais eventos por run sem
re-arquitetura). Knobs configuráveis (env, sem rebuild):

- `foxcash.notification.max-batch-size` — default **500**, teto absoluto 500
  (`MAX_BATCH_SIZE_SAFE_LIMIT`, fail-fast no boot; o pior caso da run fica
  limitado: 500 × 10s de timeout HTTP);
- `foxcash.notification.poll-interval` — default **PT10S** (30s era lento para
  drenar; 3s agrediria o DB com 0 backlog — meio-termo);
- `foxcash.notification.max-batches-per-run` (novo, default **10**) — quantos
  lotes por aquisição de lock.

- O dispatcher, após processar um lote, re-consulta `findPendingDue` em loop até
  esvaziar o backlog ou atingir o teto de lotes da run — **dentro do mesmo
  lock ShedLock** (release ao fim; o watchdog de 2min da Spec 53 continua
  cobrindo runs longas; o loop é interruption-aware).
- `SENT` continua idempotente (mesma entrega, sem duplicação); sem DLQ física;
  `FAILED`/retries/backoff intactos (Spec 35).
- **Cuidado com o lock ShedLock — decisão documentada:** o `lockAtMostFor`
  permanece **PT5M** (Etapa 1). Runs mais longas que o lock são cobertas por
  at-least-once + idempotência do receptor (já validado na Etapa 1); o
  watchdog de 2min limita a duração de cada run. Validação empírica (sem
  takeover prematuro; `job_runs` SUCCESS) fica para a campanha F4.

### 4. Benchmark de prova (regra de ouro — Spec 62)

- Re-executar a Fase 2B com o loadgen pronto: **600/s (2×300, replicas=2,
  STAGE=300, POOL=30, MAX_VUS=600)** e **750/s (3×250, replicas=3, STAGE=250,
  POOL=25, MAX_VUS=500)** — mesmos parâmetros do §11.9.
- Coletar: vendas/s sustentadas, p95 charge/webhook, `pg_stat_statements`
  (queries de auth saindo do topo), drenagem do outbox (SENT/s), dups/wallet×
  ledger/deadlocks (zona sagrada).
- Pré-requisito operacional: rate limit elevado + HTTPS relaxado (runbook
  `load-tests/k6/distributed-runbook.md`) + **restauração obrigatória** ao fim.

## Estado em memória × N instâncias (Spec 61)

| Estado | Onde | Tamanho | TTL/poda | Comportamento com N instâncias |
|---|---|---|---|---|
| Mapa do throttle do `recordUse` (Fase 1 — `lastRecordedUse` no `ApiKeyAuthFilter`) | por instância (`ConcurrentHashMap<UUID, Instant>`) | teto 10.000 entradas | poda entradas vencidas (mais antigas que o intervalo) quando passa do teto | throttle por instância: ≤ N writes/min/chave no pior caso (aceitável — o portal tem granularidade de minutos); sem cache de resolução, sem vazamento |
| Merchant do request autenticado (`AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE`) | por request (`HttpServletRequest` attribute) | 1 entidade | fim do request (GC) | nenhum — atributo de request, não compartilhado entre threads |
| (Fase 2, gated) `ApiKeyResolutionCache` | por instância (`ConcurrentHashMap` + `Clock`) | teto 10.000 | TTL 30-60s | cache por instância: TTL curto é o backstop; `invalidate(keyHash)` em revoke/regenerate é a garantia (401 imediato) |

A Fase 1 **não** adiciona cache de resolução de chave (regra de ouro: só com
evidência do re-benchmark) — apenas o mapa do throttle acima, TTL-bounded e
limitado.

## Arquivos

| Arquivo | Ação |
|---|---|
| `ApiKeyAuthFilter` | consolidar leituras; `recordUse` throttled (mapa per-chave, `ApiKeySecurityProperties`); setar merchant no request; (Fase 2) usar `ApiKeyResolutionCache` com invalidação |
| `ApiKeySecurityProperties` (**novo**) | `foxcash.security.api-key.record-use-interval` (default 60s) |
| `AuthenticatedMerchantResolver` (**novo**) | expõe o `Merchant` do request aos services (sem reload) |
| `MerchantPortalAuthFilter` | setar merchant no request (portal também reusa) |
| Services do caminho do charge | usar a entidade já carregada (sem reload; fallback ao repositório) |
| `ApiKeyManagementService` (Spec 24) | (Fase 2) `cache.invalidate` em revoke/regenerate |
| `NotificationProperties` + `NotificationDispatchScheduler` | knobs batch/poll/tetos + loop de lotes por lock |
| `application.yml` / prod | novos defaults (`record-use-interval`, batch, poll, batches-per-run) |
| `docs/capacity-report.md` | §11.10 (resultado antes/depois da Spec 65) |
| `management/specs/65-charge-path-outbox-optimization/` | documentos da spec |

## Fora de escopo

- PgBouncer/réplicas de leitura — **só** se este re-benchmark provar contenção
  de conexões/leitura-escrita (ADR-013 permite rever com evidência; o
  resultado desta spec é a evidência).
- Redis Cluster; worker externo/DLQ física do outbox; mudança de contrato de API.
- Otimização além do caminho do charge (dashboard/relatórios) — volume baixo.
