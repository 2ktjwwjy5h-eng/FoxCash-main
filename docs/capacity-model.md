# Capacity Model — custo de 1 venda por camada (Spec 62, Fase 0)

> **Status:** `implemented` (task 1/2 da F0 concluídas; `pg_stat_statements` no
> Railway **INSTALADO E COLETANDO em 2026-08-08** — ver §4).
>
> **Metodologia:** baseline **medido**, não estimado. App local (perfil `dev`,
> PostgreSQL 18.4 nativo, pool Hikari 20, PSP mock, rate limit `memory`) com
> `spring.jpa.show-sql=true`. 1 venda = **criação da cobrança**
> (`POST /api/v1/nyx/pix/cob`) + **confirmação de pagamento**
> (`POST /webhooks/pix` `PAID`). Queries contadas por janela limpa (sem ruído do
> dispatcher de outbox, que roda a cada 30s — verificado nas janelas).

---

## 1. Custo de 1 venda (medido)

| Camada | Operação | Qtd | Detalhe |
|---|---|---|---|
| **Requests HTTP** | 2 | 1 `POST /api/v1/nyx/pix/cob` + 1 `POST /webhooks/pix` | o gerador de carga (F7) dirige ambos |
| **Queries PostgreSQL** | **28** | 18 na cobrança + 10 no webhook | SQL capturado via `show-sql` (Anexo A) |
| — *leituras (SELECT)* | 18 | 13 cobrança + 5 webhook | inclui 2 locks `FOR NO KEY UPDATE` no webhook |
| — *escritas (INSERT/UPDATE)* | **10** | 5 cobrança + 5 webhook | ver §2 |
| **Operações Redis** | **2** | 1 por request (rate limit `store=redis`) | 0 com `store=memory` (default local) |
| **Eventos de outbox** | **2** | `CHARGE_CREATED` + `SALE_PAID` | gravados na MESMA transação do fluxo |
| **Entregas de webhook de saída** | 2 | 1 por evento (se webhook configurado) | **assíncronas** (dispatcher Spec 35) — fora do caminho crítico |
| **Spans de tracing** | ~30 | HTTP (2) + JDBC (28) + Redis (2) | sampling 100% no benchmark |

### 1.1 Quebra da cobrança (18 queries)

| # | SQL (resumo) | Tipo | Origem |
|---|---|---|---|
| 1–6 | `select merchants` ×2, `select merchant_api_keys` ×3, `update merchant_api_keys last_used_at` | leitura ×5 + escrita | **filtro de auth** (Spec 12/24) — carrega o lojista 2× e a chave 3× |
| 7 | `select merchants where id=?` | leitura | `CreatePixChargeService.findById` (recarrega o lojista já carregado no auth) |
| 8 | `select count(chargebacks) where buyer_id_hash=?` | leitura | antifraude em tempo real (Spec 19) |
| 9 | `select pix_transactions where merchant_id+idempotency_key` | leitura | idempotência (Spec 28) |
| 10 | `select count(pix_transactions) where merchant_id+status=PENDING` | leitura | cap de pendentes (Spec 58) |
| 11–12 | `select pix_transactions by id` + `insert pix_transactions (PENDING)` | leitura + **escrita** | `IdempotencyPersister` (Spec 28, transação própria `REQUIRES_NEW`) |
| 13–16 | `select by id` + `insert notification_outbox (CHARGE_CREATED)` + `update pix_transactions (attach)` + `select by id` | **escrita ×2** + leitura ×2 | `attachAndPublish` (Spec 28/35) — QR + evento na mesma transação |
| 17–18 | `select platform_activity_log` + `insert platform_activity_log (CHARGE_CREATED)` | **escrita** + leitura | relatório de atividades (Spec 41) |

### 1.2 Quebra do webhook PAID (10 queries)

| # | SQL (resumo) | Tipo | Origem |
|---|---|---|---|
| 1 | `select pix_transactions where external_reference=? FOR NO KEY UPDATE` | leitura (lock) | serializa reentregas concorrentes (Spec 26/28) |
| 2 | `select merchants where id=? FOR NO KEY UPDATE` | leitura (lock) | lock do wallet — evita perda de atualização (Spec 26) |
| 3–5 | `select wallet_ledger by id`, `select notification_outbox by id`, `select platform_activity_log by id` | leitura | save/insert com id gerado |
| 6 | `insert wallet_ledger (SALE_CREDIT)` | **escrita** | ledger do wallet (Spec 27) |
| 7 | `insert notification_outbox (SALE_PAID)` | **escrita** | webhook de saída (Spec 35) |
| 8 | `insert platform_activity_log (SALE_PAID)` | **escrita** | atividade (Spec 41) |
| 9 | `update pix_transactions (status=APPROVED, paid_at)` | **escrita** | aprovação |
| 10 | `update merchants (wallet_balance)` | **escrita** | crédito do saldo |

---

## 2. Conversão para a meta (5.000 vendas/s)

Regra de ouro: **sem evidência não se declara a meta** — este modelo dimensiona o
custo; o número REAL sai do benchmark (F7/F10). A conversão só mostra o que
"5.000 vendas/s" significa por camada:

| Camada | 1 venda | × 5.000 vendas/s | Observação |
|---|---|---|---|
| Requests HTTP | 2 | **10.000 req/s** | ~8.500/s por instância com 2 réplicas (Spec 61) |
| Queries PostgreSQL | 28 | **140.000 queries/s** | ~7.000/s por conexão a ~5ms (Spec 60) |
| Escritas PostgreSQL | 10 | **50.000 writes/s** | caminho de escrita transacional (ledger/wallet/outbox) |
| Operações Redis | 2 | **10.000 ops/s** | só com `NYX_RATE_LIMIT_STORE=redis` (multi-instância) |
| Eventos de outbox | 2 | **10.000 eventos/s** | consumer assíncrono fora do caminho crítico |
| Webhook de saída | 2 | **10.000 deliveries/s** | dispatcher + retry (Spec 35/53) |

**Achados preliminares (a confirmar na F1 com `pg_stat_statements` no Railway):**

1. **Duplicação de load no auth (suspeita de N+1/loads redundantes):** o
   `ApiKeyAuthFilter` carrega `merchants` 2× e `merchant_api_keys` 3× no mesmo
   request; o `CreatePixChargeService` recarrega o `merchants` pela 3ª vez.
   **Custo estimado: ~5-6 SELECTs redundantes por venda (~25-30k queries/s na
   meta)** — candidato a correção cirúrgica na F1 (ex.: cache no principal,
   join, ou evitar o reload).
2. **Locks pessimistas `FOR NO KEY UPDATE`** (não `FOR UPDATE` — bom): 2 locks
   por venda no webhook. A contenção será medida na F1 (task 4) sob carga.
3. **Caminho crítico da venda já curto** (Spec 28/35): PSP fora da transação,
   outbox na mesma transação, dispatcher assíncrono — alinhado ao critério 7.

---

## 3. Métricas de observabilidade confirmadas (task 1, partes 2 e 3)

| Instrumentação | Estado | Evidência |
|---|---|---|
| Métricas Micrometer `nyx_*` (Spec 53) | ✅ expostas | `/actuator/prometheus`: `nyx_job_duration_seconds_*`, `nyx_job_success_total`, `nyx_job_failure_total` (tag `job=...`); `hikaricp_connections_*` disponíveis |
| Tracing distribuído (Spec 57) | ✅ disponível | `NYX_TRACING_SAMPLING_PROBABILITY` (default 0.1; **1.0 durante o benchmark**) + `NYX_OTLP_ENDPOINT`; spans HTTP/JDBC/Redis |
| `pg_stat_statements` | ⏳ **pendente (Railway)** | ver §4 — ação de infra do usuário |

---

## 4. `pg_stat_statements` no Postgres do Railway — ✅ EXECUTADO (2026-08-08)

Pré-requisito da F1 (task 1 da F0, e tasks 3–5) — **resolvido em 2026-08-08**
via túnel (`railway connect Postgres`) com o usuário admin (dono do
`DATABASE_URL`):

1. `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` → **OK** (não precisou
   de `shared_preload_libraries` — o Railway já o tinha habilitado).
2. Verificação pós-instalação:
   - `SHOW shared_preload_libraries;` → `pg_stat_statements` ✅
   - `SELECT count(*) FROM pg_stat_statements;` → **839** (coletando o tráfego
     de produção) ✅
3. Para o benchmark (F7), resetar por estágio com:
   ```sql
   SELECT pg_stat_statements_reset();
   ```
   e consultar o top por estágio com:
   ```sql
   SELECT calls, total_exec_time, mean_exec_time, rows, query
   FROM pg_stat_statements
   ORDER BY total_exec_time DESC LIMIT 20;
   ```

**Nota local (dev):** o Postgres 18 local (Windows) **não** tem
`shared_preload_libraries=pg_stat_statements` (requer restart + editar
`postgresql.conf` como admin — sem acesso superuser nesta máquina). As medições
locais da F0 usaram `spring.jpa.show-sql` (equivalente funcional para contagem);
o `pg_stat_statements` entra para as medições de produção no Railway (F1/F7).

---

## Anexo A — Log de SQL capturado (janela limpa)

Fluxo completo (lojista `clean<ts>`, cobrança 49.90, idempotencyKey `clean-<ts>`):

```
--- COBRANÇA (18 queries) ---
select merchants where id=?                                [auth: load lojista]
select merchant_api_keys where key_hash=?                  [auth: lookup chave]
select merchants where id=?                                [auth: reload lojista]
select merchant_api_keys where id=?                        [auth: entity p/ update]
select merchant_api_keys where id=?                        [auth: entity p/ update]
update merchant_api_keys set last_used_at=? where id=?     [auth: último uso]
select merchants where id=?                                [service: findById]
select count(chargebacks) where buyer_id_hash=? and created_at>?   [antifraude]
select pix_transactions where merchant_id=? and idempotency_key=?  [idempotência]
select count(pix_transactions) where merchant_id=? and status=?    [cap pendentes]
select pix_transactions where id=?                          [IdempotencyPersister save]
insert into pix_transactions (PENDING)                      [persist pending]
select pix_transactions where id=?                          [attach]
select notification_outbox where id=?                       [outbox save]
insert into notification_outbox (CHARGE_CREATED)            [evento outbox]
update pix_transactions set external_reference,qr,copy_paste where id=?  [attach]
select platform_activity_log where id=?                     [atividade save]
insert into platform_activity_log (CHARGE_CREATED)          [atividade]

--- WEBHOOK PAID (10 queries) ---
select pix_transactions where external_reference=? FOR NO KEY UPDATE  [lock tx]
select merchants where id=? FOR NO KEY UPDATE               [lock wallet]
select wallet_ledger where id=?                             [ledger save]
select notification_outbox where id=?                       [outbox save]
select platform_activity_log where id=?                     [atividade save]
insert into wallet_ledger (SALE_CREDIT)                     [ledger]
insert into notification_outbox (SALE_PAID)                 [evento outbox]
insert into platform_activity_log (SALE_PAID)               [atividade]
update pix_transactions set status=APPROVED, paid_at where id=?        [aprova]
update merchants set wallet_balance=? where id=?            [crédito saldo]
```
