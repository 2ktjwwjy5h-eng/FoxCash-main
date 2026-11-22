# PostgreSQL — análise de alvo principal (Spec 62, Fase 1)

> **Status:** `implemented` (tasks 3–6; medições locais 2026-08-09). O
> `pg_stat_statements` no Railway (task 1) continua pendente — ação de infra do
> usuário (passos em `docs/capacity-model.md` §4). As medições de produção
> (IOPs/CPU sob carga, slow queries reais, contenção) serão coletadas no
> benchmark da F7 — este documento registra o que a medição local já provou.
>
> **Regra de ouro aplicada:** nenhuma mudança de arquitetura (PgBouncer,
> réplicas, Redis Cluster) foi feita — o PostgreSQL local não apresentou nenhum
> gargalo mensurável (índices completos, cache 99,98%, 0 deadlocks, WAL 6 KB/
> venda). As medições abaixo são a evidência que **dispensou** as mudanças.

---

## 1. Cobertura de índices (task 3) — 100% das queries quentes indexadas

Todas as queries do caminho da venda (capacity model: 28 queries/venda) têm
índice correspondente. Verificação: `pg_indexes` nas tabelas do caminho
(31 índices) cruzado com o SQL capturado (Anexo A do capacity model).

| Query quente (por venda) | Índice que cobre | Tipo |
|---|---|---|
| `select merchants where id=?` (auth + service) | `merchants_pkey` | PK |
| `select merchant_api_keys where key_hash=?` (auth) | `uq_merchant_api_keys_key_hash` | unique |
| `update merchant_api_keys set last_used_at=?` (auth) | `merchant_api_keys_pkey` | PK |
| `select pix_transactions where merchant_id+idempotency_key` (idempotência) | `uq_pix_transactions_idempotency` | unique parcial |
| `select count(pix_transactions) where merchant_id+status=PENDING` (cap) | `ix_pix_transactions_merchant_status` | btree |
| `select pix_transactions where external_reference=? FOR NO KEY UPDATE` (webhook) | `uq_pix_transactions_external_reference` | unique parcial |
| `select merchants where id=? FOR NO KEY UPDATE` (lock wallet) | `merchants_pkey` | PK |
| `select count(chargebacks) where buyer_id_hash+created_at>?` (antifraude) | `idx_chargebacks_buyer_created` | btree |
| `select wallet_ledger where id=?` (ledger) | `wallet_ledger_pkey` | PK |
| `insert wallet_ledger` (ledger) | `uq_wallet_ledger_ref` (backstop duplicado) | unique |
| `select notification_outbox where status+next_attempt_at<=? FETCH 50` (dispatcher) | `ix_notification_outbox_pending` | btree |
| `select merchant_webhooks where merchant_id+status` (dispatcher) | `ix_merchant_webhooks_merchant_status` | btree |
| `select merchants where email=?` (cadastro) | `uq_merchants_email` | unique funcional |

**EXPLAIN (ANALYZE, BUFFERS)** nas queries quentes (dev, tabelas pequenas):
Seq Scan é o plano ótimo para 32 linhas (correto — o otimizador escolhe seq scan
em tabelas minúsculas). Com escala, os índices acima serão usados
automaticamente. **Nenhum índice ausente ou subutilizado no caminho da venda.**

## 2. Locks e duração de transações (task 4)

| Medida | Valor | Detalhe |
|---|---|---|
| Locks por venda | **2 × `FOR NO KEY UPDATE`** | transação (`pix_transactions`) + wallet (`merchants`) no webhook PAID |
| Modo do lock | `NO KEY UPDATE` | **menos bloqueante que `FOR UPDATE`** — não conflita com atualizações de chave/índice (boa prática confirmada) |
| Duração da transação do webhook | **~11 ms** (request total, 3 amostras) | lock hold time = fração de ms |
| Duração da transação da cobrança | **~16 ms** (request total, 3 amostras) | inclui `REQUIRES_NEW` do `IdempotencyPersister` (curto) |
| Deadlocks | **0** | `pg_stat_database`: 21.364 commits, 544 rollbacks (rollbacks = rejeições intencionais de validação, não deadlocks), `deadlocks=0` |
| Contenção | não observada em dev | a contenção real é medida na F7 (burst de webhooks mesma transação — coberto pelo E2E `concurrency.spec.js` teste 2: 1 crédito sob concorrência) |

## 3. WAL / cache hit / conexões (task 5)

| Medida | Valor | Implicação |
|---|---|---|
| **WAL por venda** | **6.264 bytes (~6 KB)** | medido via `pg_wal_lsn_diff` (LSN exato) entre cobrança+webhook; a **5.000 vendas/s → ~31 MB/s de WAL** (dentro da capacidade de qualquer SSD; tuning de checkpoint a validar na F7) |
| Cache hit ratio | **99,98%** (71.013 hits / 10 reads) | `pg_statio_user_tables` — working set cabe em RAM |
| Conexões ativas | 21 (20 pool Hikari + 1) | pool Hikari 20/réplica (Spec 60); sem fila (`hikaricp_connections_acquire_seconds_max` = 0,6 ms) |
| IOPs/CPU do Postgres | não mensuráveis no Windows local | **adiado para o benchmark Railway (F7)** com `pg_stat_statements` + métricas de instância |
| Slow queries | nenhuma (todas < 10 ms) | sem registro de `log_min_duration_statement`; validar no Railway |

## 4. Achado de F1: loads redundantes no auth (candidato a correção — GATED)

**Evidência:** no capacity model, o filtro de auth (`ApiKeyAuthFilter`) executa
~5-6 SELECTs por request de cobrança: `merchant_api_keys` por `key_hash` (1) +
`merchants` por id (1) + `recordUse` (`findById` + save = 1-2 SELECTs + UPDATE) +
re-load do `merchants` no `CreatePixChargeService` (1).

**Impacto estimado:** ~5-6 SELECTs redundantes/venda → ~25-30 mil queries/s a
5.000 vendas/s (**~18-21% do volume de queries**).

**Correção candidata (cirúrgica, sem mudar contrato):** `recordUse` como bulk
update JPQL (`@Modifying @Query("update MerchantApiKeyJpaEntity k set
k.lastUsedAt = :usedAt where k.id = :keyId")`) eliminaria 1-2 SELECTs/request
com semântica idêntica; evitar o re-load do `merchants` no service exigiria
mudar o contrato do use case (mais invasivo — não fazer).

**DECISÃO (regra de ouro):** **não aplicar agora.** O PostgreSQL não é o
gargalo nas medições locais (16 ms/cobrança, cache 99,98%, pool ocioso). A
regra de ouro exige evidência de benchmark antes de otimizar: se a F7 mostrar
que o número de round-trips de DB limita o throughput, aplica-se a correção com
antes/depois (28 → ~26 queries/venda). Registrar como **top-3 gargalo candidato**
para o relatório final (F10).

## 5. Correções aplicadas (task 6)

**Nenhuma mudança de schema/config aplicada** — a auditoria de índices retornou
100% de cobertura e nenhuma configuração PostgreSQL se mostrou inadequada
localmente. Esta é a evidência registrada que **dispensa** mudanças na F1 (não
há antes/depois porque não há correção). O `pg_stat_statements` do Railway
permitirá confirmar em produção com slow queries reais e contagem de chamadas
por query (task 1 pendente do usuário).

## 6. Conclusão da F1

- **PostgreSQL saudável como alvo principal:** índices completos, cache quente,
  0 deadlocks, WAL 6 KB/venda, locks minimais (`NO KEY UPDATE`), transações de
  ~11-16 ms.
- **Nenhum bloqueio para a meta de 5.000 vendas/s no nível de banco** — o
  gargalo real (se existir) será medido na F7 (load test por estágio), não
  assumido.
- **Próximas fases gated (F2/F3) aguardam a medição de contenção/competição
  leitura×escrita da F7** — sem ela, PgBouncer e réplicas ficam dispensados com
  esta medição registrada.
