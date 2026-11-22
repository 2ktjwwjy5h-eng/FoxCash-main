# Redis e Outbox — análise (Spec 62, Fases 5 e 6)

> **Status:** F5 parte local + F6 task 21 concluídas (2026-08-09). As medições
> de Redis sob carga (latência/throughput/eviction/failover) e o backlog do
> outbox por estágio dependem do benchmark no Railway (F7) — registradas como
> pendências de infra (o usuário ativa o `pg_stat_statements` e a infra do
> Railway; ver `docs/capacity-model.md` §4).

---

## Fase 5 — Redis como infraestrutura de escala (parte local)

### Medições locais (2026-08-09)

| Item | Resultado | Evidência |
|---|---|---|
| **Fail-open com Redis down (task 18)** | ✅ **confirmado ao vivo** | App com `NYX_RATE_LIMIT_STORE=redis` + `SPRING_DATA_REDIS_PORT=6399` (Redis inexistente): **boot OK (health 200)**, venda completa (charge 201 + webhook 204), **0 429/0 5xx**, WARN no log: `RedisRateLimitStore - Rate limit Redis indisponivel — fail-open (permitindo requisicao): Unable to connect to Redis` (ADR-010) |
| **Atomicidade do rate limit (task 19)** | ✅ coberto (Spec 43) | script Lua `INCR`+`EXPIRE` atômico testado em `RedisRateLimitStoreTest` (Spec 43, `audited`); re-verificação sob concorrência real → benchmark Railway (F7) |
| **Latência/throughput/conexões/pool/memória/eviction (task 17)** | ⏳ **Railway (F7)** | sem Redis local (Windows, sem Docker); o Railway Redis já está em uso pelo rate limit (Spec 61) |
| **Failover (task 18)** | ⏳ **Railway (F7)** | failover gerenciado do Railway; comportamento com Redis down já provado acima (fail-open) |
| **Redis Cluster (task 20)** | ⏳ **decisão gated** | só se o benchmark demonstrar que o Redis single é gargalo (regra de ouro); sem evidência → manter single + documentar |

**Impacto do fail-open medido:** com Redis down, o rate limit deixa de
funcionar (permitindo tráfego) mas o sistema NÃO degrada em 5xx/latência —
custo = 1 WARN throttled por janela + chamada Redis que falha em ~ms (timeout
curto do Lettuce). Este é o comportamento documentado no ADR-010.

### Conclusão F5 (local)

- O Redis é **infraestrutura de escala válida**: rate limit agregado entre
  réplicas (Spec 61/5c) + fail-open que nunca derruba o gateway.
- Decisão de **Redis Cluster adiada** (sem evidência de gargalo); as medições
  de produção entram na F7.

---

## Fase 6 — Outbox/caminho assíncrono (task 21 — auditoria do caminho crítico)

### Caminho crítico da venda (auditado no código, 2026-08-09)

**Cobrança (`POST /api/v1/nyx/pix/cob`):**
`REQUEST → validate (contrato) → auth (DB) → rate limit → idempotency (DB) →
antifraude (DB) → cap pendentes (DB) → PENDING persist (DB, REQUIRES_NEW) →
PSP createCharge (HTTP, FORA da transação DB — Spec 28/M1a) → attach + outbox
CHARGE_CREATED (DB, 1 tx) → activity (DB, 1 tx) → COMMIT → RESPONSE`

**Webhook PAID (`POST /webhooks/pix`):**
`assinatura HMAC (CPU) → rate limit webhook → lock tx FOR NO KEY UPDATE (DB) →
lock wallet (DB) → approve + credit + ledger SALE_CREDIT + outbox SALE_PAID +
activity + delivery (se plano) → COMMIT → RESPONSE`

### Achados da auditoria

1. ✅ **Nenhuma chamada síncrona secundária no caminho crítico** — sem
   RestTemplate/WebClient/HttpClient nos services do caminho da venda
   (verificado por grep). O PSP é a chamada **primária** (gera o QR) e roda
   **fora da transação DB** (Spec 28 — a conexão do pool não fica retida no I/O
   de rede). Webhook de saída/email/bot/delivery são **assíncronos** via outbox
   (dispatcher agendado 30s, lote 50, transações próprias — Spec 35/53).
2. ❌→✅ **Duplicação de `CHARGE_CREATED` sob corrida de retries** (achado da
   F4): N threads com a MESMA `idempotencyKey` viam a PENDING sem
   `external_reference` e republicavam o evento (medido: **26 eventos/1
   transação**). **Corrigido** em `PixChargePersistence.attachAndPublish`
   (lock `findByIdForUpdate` + só o 1º publica) → re-teste: **1 evento/1
   transação**. Commit `cf76d73`, `mvn test` 1102/0/0.
3. ✅ **Eventos financeiros protegidos por lock + gate de status** — o
   `SALE_PAID` (crédito do wallet) é serializado pelo `FOR NO KEY UPDATE` na
   transação + gate `PENDING→APPROVED` (idempotente, replay 204 — Spec 18/29).

### Backlog do outbox por estágio (task 22)

⏳ **Railway (F7)** — medir `notification_outbox` PENDING por estágio de carga
(backlog deve escoar: dispatcher 30s × lote 50 × pool ≥ 4). A F4 já provou que
o outbox fica com 1 evento por transação no caminho feliz.

---

## Próximos passos (dependem de infra Railway)

- **F5 completo + F7:** benchmark por estágio com o Redis do Railway +
  `pg_stat_statements` (ação do usuário) + rate limit elevado.
- **F8/F9:** explosão e scale-out (2→16 instâncias) no Railway.
- **F10:** consolidar `docs/capacity-report.md` com os números reais.
