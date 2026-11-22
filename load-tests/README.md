# Testes de carga e concorrência (k6)

Testes de performance do backend FoxCash com [k6](https://k6.io/).

## Cenários

| Script | Cenário | O que valida |
|---|---|---|
| `load.js` | Smoke (1 VU) + Load (rampa até 20 VUs) + Stress (100 VUs) | Endpoints respondem; p95 < 400ms no load; < 1% de erros |
| `concurrency.js` | Burst de N VUs **simultâneas** (default 50, `--env VUS=200`) | Comportamento sob rajada concorrente (pool, locks, corrida) |
| `critical-endpoints.js` | Load dos endpoints críticos da loja (storefront, deliveries/pending, webhook PAID na MESMA cobrança) | Custo do LOCK da transação sob contenção + idempotência (204 sempre, 1 crédito) |
| `rate-limit-calibration.js` | **Calibração do rate limit por principal (Spec 58)** na infra real — cada run cria lojista novo e dispara N VUs em `GET /api/v1/merchants/{id}` com API key | Degradação controlada: 200 (aceitos) vs 429 (teto) vs 5xx/conn-error (NUNCA deve haver) + p95 dos aceitos (ponto de inflexão) |
| `webhook-flood-calibration.js` | **Calibração do rate limit de webhooks por IP (Spec 59)** — burst de N POSTs em `/webhooks/pix` com assinatura INVÁLIDA (o 429 corta antes do HMAC; sem risco de processar pagamento) | 401 (aceitos) vs 429 (teto 120/min/IP) vs 5xx/conn-error (NUNCA); a contagem de 429 revela o store: `redis` → 429 ≈ FLOOD−120 (agregado, Spec 61/5c), `memory` → ≈ FLOOD−240 (por instância) |
| `idempotency-concurrency.js` | **Idempotência em throughput (Spec 62/F4)** — 10.000 requests CONCORRENTES com a MESMA `idempotencyKey` (500 VUs, `--env VUS=`/`ITERATIONS=`) | 1 transação (verificar no banco), 0 duplicações, 0 5xx; 409 `idempotency_conflict` (M-A2) contado à parte; o outbox deve ter **1** `CHARGE_CREATED` por transação |
| `capacity-stages.js` | **Capacidade por estágio (Spec 62/F7)** — 1 venda = cobrança + webhook PAID; `constant-arrival-rate` com `--env STAGE=` (vendas/s) e `--env DURATION=` | vendas/s ALCANÇADAS (unidade de medida), p95/p99 por endpoint, 0 5xx (rate limit elevado na app — task 24); relatório registra VUs E vendas/s |

`load.js`/`concurrency.js` exercitam a **stack completa**: login do master
(BCrypt + sessão + DB) e rota autenticada de ADMIN (`GET /api/v1/admin/merchants`
→ auth filter + service + DB). `rate-limit-calibration.js` não precisa de
credenciais — o setup registra um lojista via rota pública (bucket por
principal fresco por run).

## Pré-requisitos

1. **Instalar k6** ([guia oficial](https://k6.io/docs/get-started/installation/)):
   ```bash
   # macOS
   brew install k6
   # Debian/Ubuntu (ver guia oficial para a chave atual)
   sudo apt-get install k6
   # Windows (choco)
   choco install k6
   # Windows (winget)
   winget install k6.k6
   ```

2. **Subir a aplicação** (perfil dev, com rate limit desligado para não interferir):
   ```bash
   # Env correta: FOXCASH_SECURITY_RATE_LIMIT_ENABLED (binding relaxado Spring).
   # FOXCASH_RATE_LIMIT_ENABLED NÃO funciona — o prefixo é foxcash.security.rate-limit.
   FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
   ```

## Executar

```bash
# Load completo (smoke + load + stress)
k6 run load-tests/k6/load.js --env BASE_URL=http://localhost:8080

# Apenas o cenário de load
k6 run load-tests/k6/load.js --env BASE_URL=http://localhost:8080 --scenario load

# Concorrência: 50 requisições simultâneas
k6 run load-tests/k6/concurrency.js --env BASE_URL=http://localhost:8080

# Concorrência pesada: 200 simultâneas
k6 run load-tests/k6/concurrency.js --env BASE_URL=http://localhost:8080 --env VUS=200
```

## Thresholds (falha se não atingir)

| Métrica | Limite |
|---|---|
| `http_req_failed` | < 1% de erros |
| `http_req_duration` | p(95) < 1000ms |
| `http_req_duration{scenario:load}` | p(95) < 800ms |
| `http_req_duration{scenario:stress}` | p(95) < 1500ms |
| `http_req_duration` (concurrency) | p(95) < 1000ms (padrão; `--env P95_MAX_MS=...` sobrepõe) |

> **Linha de base real (dev, 12.782 reqs, 0% falhas): p95 = 718ms.** O gargalo é o
> login BCrypt (~50-100ms CPU/hash). Os limites acima são a linha de base; para
> meta agressiva de produção, baixar para p(95) < 400ms após otimizar o BCrypt
> (ex.: cache/custo do hash) ou medir em hardware de produção.
>
> **CI (GitHub Actions, runner 2 vCPUs):** o job `load` roda a concorrência
> (50 VUs) com `--env P95_MAX_MS=5000` — no runner compartilhado o p95 observado
> é ≈3.75s (50 logins BCrypt simultâneos são CPU-bound em 2 vCPUs). O limite
> estrito p95<1000ms permanece no script como default para execução local/manual;
> o env permite ajustar o envelope sem tocar no script.

## CI

O job `load` no GitHub Actions (`ci.yml`) roda o cenário de **concorrência**
(50 VUs simultâneas) contra a aplicação real + Postgres — regressão de
performance a cada push/PR. Para cenários longos (load/stress), rodar
localmente ou em um runner dedicado.

## Notas

- **Rate limit**: o `RateLimitFilter` limita a 20 req/min/IP em produção —
  proteção de abuso, não alvo deste teste. Suba com
  `FOXCASH_SECURITY_RATE_LIMIT_ENABLED=false` para a carga não receber 429
  (env do binding `foxcash.security.rate-limit.enabled`).
- **Seed**: o `MasterSeedRunner` cria o master `master`/`dev-only-master-seed-password`
  no boot dev (application-dev.yml). O teste de login depende disso.
- **Logins repetidos criam sessões**: cada iteração cria uma sessão nova — o
  teste de carga gera centenas de sessões no `master_sessions`. Sem impacto
  funcional, mas espere tabelas maiores após rodar.
