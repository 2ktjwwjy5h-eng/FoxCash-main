---
name: Rate limit por usuário/chave e cobertura ampliada
status: audited
references:
  - "management/vision.md#V38"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/43-rate-limit-redis/requirements.md"
  - "management/specs/28-charge-idempotency/requirements.md"
---

# Spec 58 — Rate limit por usuário/chave e cobertura ampliada

## O que entrega

Corrige o achado **CRÍTICO** da análise de resiliência (2026-08-07): o rate
limit atual (Spec 18/43) cobre apenas 5 rotas públicas por IP e **não enxerga o
usuário autenticado** — um lojista com API key (ou chave vazada) pode gerar
carga ilimitada no core transacional. Entregáveis:

1. **Rate limit por principal autenticado** — novo filtro que limita rotas
   transacionais por **API key/token** (merchantId, masterId ou chave admin),
   reutilizando a porta `RateLimitStore` (memory/redis, Spec 43).
2. **Cap de cobranças pendentes por lojista** — teto de `PENDING` por conta
   (`409 too_many_pending_charges`), evitando que 1 lojista acumule cobranças
   ilimitadas (custo PSP + ledger).
3. **Rate limit por token em admin/master** — endpoints caros
   (`/api/v1/master/**`, `/api/v1/admin/**`) limitados por token.
4. **Cobertura por IP ampliada** — rotas transacionais autenticadas
   (`/transactions`, `/billing`, `/nyx/deliveries`, `/portal/dashboard`)
   ganham limite por IP (rede de segurança, teto maior que o das públicas).

## Critérios de aceite

1. Flood com a MESMA API key em rota transacional (ex.: `POST /api/v1/foxcash/pix/cob`)
   → **429 `too_many_requests`** após o teto; requisições legítimas abaixo do
   teto passam (201).
2. Loja diferente (outra API key) **não é afetada** pelo limite da primeira
   (chave por principal, não global).
3. Bot polling (`GET /api/v1/foxcash/deliveries/pending`) **não** dispara 429 com a
   frequência documentada do bot.
4. Cap de pendentes: lojista com N `PENDING` ativas (teto configurável) → próxima
   criação → **409 `too_many_pending_charges`**; após pagamento (APPROVED), a
   criação volta a funcionar.
5. Flood com token master em `/api/v1/master/report/activity` → **429** após o
   teto; admin com `size=100` normal passa.
6. Rotas ampliadas por IP: flood de IP → 429 (teto maior); sem IP compartilhado
   → sem falso positivo.
7. `mvn test` sem regressão; E2E sem regressão (fluxos existentes usam poucas
   requisições — abaixo de qualquer teto).
8. Com `store=redis` (Spec 43), o contador por principal é agregado entre
   instâncias (mesma chave `rl:{rota}|{principal}` no script Lua).

## Filosofia de calibração (baseline ≠ definitivo)

Os valores propostos (tetos por chave/IP, cap de pendentes) são **baseline
inicial**, NÃO metas finais. O objetivo não é maximizar requisições, e sim
**degradação controlada sob sobrecarga**: quando a capacidade é excedida, o
sistema deve cortar o excesso com **429** (antes de saturar CPU/DB), mantendo a
latência dos aceitos estável e **sem** OOM, restart loop, corrupção de ledger,
perda de outbox ou quebra de idempotência.

Calibração obrigatória no Railway (benchmark k6 com ramp progressivo): medir o
ponto de inflexão da latência p95 e ajustar os tetos para operar **antes** dele;
re-teste após cada ajuste. Resultados e valores finais registrados no roadmap.

## Critérios de integridade sob sobrecarga

9. **Idempotência preservada:** replay de cobrança com `idempotencyKey`
   existente continua devolvendo a MESMA transação (200/201, nunca 409), mesmo
   quando o cap de pendentes foi atingido — a checagem de idempotência ocorre
   ANTES do cap (Spec 28).
10. **Ledger íntegro:** após pico, `wallet_balance = Σ ledger` por lojista
    (reconciliação da Spec 30 passa; zero divergência).
11. **Outbox consistente:** nenhum evento perdido sob sobrecarga — retry/backoff
    da Spec 35/53 respeitado; contagem de eventos PENDING/FAILED coerente após
    o pico.
12. **Sem OOM/restart:** heap monitorado (`jvm.memory.used`), zero
    `OutOfMemoryError` e zero restart loop no pico.

## Auditoria 2026-08-07

**Resultado: 0 issues** (implementação a881caa, `mvn test` 1094/0/0).

Critérios verificados e evidência:

| Critério | Evidência |
|---|---|
| 1 (429 por chave / 201 abaixo do teto) | `AuthenticatedRateLimitFilterTest` 9/9; `CreatePixChargeServiceTest` 26/26; **verificação ao vivo** `load-tests/verify-rate-limit-429.py` (5×200 + 2×429) |
| 2 (isolamento por principal) | `differentPrincipalsDoNotShareTheBucket` |
| 3 (bot polling OK) | `botPollingGetOnDeliveriesIsRateLimitedByIp` (teto folgado 300/min) |
| 4 (cap 409; APPROVED libera) | contagem é por `status=PENDING` — APPROVED não conta; `rejectsNewChargeWhenPendingCapReached`/`allowsChargeWhenPendingBelowCap` |
| 5 (master/admin por token) | filtro genérico (todo `/api/v1/**` com principal autenticado); coberto por design — sem teste dedicado (gap menor, mecanismo path-agnóstico) |
| 6 (cobertura IP ampliada) | `RateLimitFilterTest` tier transacional 18/18; **ao vivo**: 20×401 + 2×429 |
| 7 (`mvn test` / E2E) | `mvn test` 1094/0/0 ✅; E2E Playwright **104/104 re-executado** (2026-08-07, rate limit default ativo — ver seção "Re-execução da suíte E2E") ✅ |
| 8 (redis multi-instância) | mesma porta `RateLimitStore` (contrato da Spec 43, testado com Lua/redis) — filtro ignora o store concreto |
| 9 (**idempotência antes do cap**) | `idempotentReplayStillConvergesWhenPendingCapReached` + ordem no código: lookup de idempotência (L103) ANTES do `ensurePendingChargesBelowCap` (L112) |
| 10-12 (ledger/outbox/OOM sob pico) | **não verificável localmente** — dependem do benchmark Railway (tasks 10-11, follow-up pós-deploy) |

**Follow-ups registrados (não bloqueantes):** benchmark de calibração Railway
(k6) com ajuste dos tetos; re-execução da suíte E2E Playwright; teste dedicado
de flood por token master/admin.

## Benchmark Railway (2026-08-07, pós-deploy `2f47122e`)

Executado contra `https://nyxcash-production.up.railway.app` (região sfo) com o
novo script `load-tests/k6/rate-limit-calibration.js` — cada run cria um
lojista NOVO (bucket por principal fresco) e dispara N VUs **simultâneas**
(1 iteração cada) em `GET /api/v1/merchants/{id}` com `X-Api-Key` (rota
autenticada sem bucket de IP — mesmo padrão da camada A do
`verify-rate-limit-429.py`). Tetos baseline ativos (authenticated 120/min).

| VUs | 200 (aceitos) | 429 (teto) | conn-error | p95 aceitos | 5xx | health pós |
|---|---|---|---|---|---|---|
| 50 | 50 | 0 | 0 | 612ms | 0 | 200 |
| 100 | 100 | 0 | 0 | 603ms | 0 | 200 |
| 500 | **120** | **380** | 0 | 903ms | 0 | 200 |
| 1.000 | **120** | 793 | 87 (8,7%) | 768ms | 0 | 200 |
| 2.000 | **120** | 784 | 1.096 (54,8%) | 774ms | 0 | 200 |
| 5.000 | **120** | 782 | 4.098 (82%) | 682ms | 0 | 200 |

**Leitura:**

1. **O teto por principal é aplicado com precisão cirúrgica em TODOS os níveis:**
   exatamente 120×200 + ~780×429 por janela — a degradação controlada
   (Spec 58) funciona na infra real.
2. **Zero 5xx em todos os níveis e health UP após cada run** — a app nunca
   satura: o 429 corta o excesso ANTES de CPU/DB. Sem OOM/restart nos logs
   (único ERROR é o exporter OTLP pré-existente da Spec 57).
3. **O gargalo real da infra é a camada de CONEXÃO:** refusals começam em
   ~1.000 conexões simultâneas (8,7%) e crescem (82% em 5.000). A latência
   dos ACEITOS permanece estável (~600-900ms p95) mesmo com 5.000 conexões —
   o serviço atrás da fila de accept está saudável.
4. **Conclusão de calibração:** o baseline de 120/min por principal é
   adequado (a app não degrada). Como o teto de conexões (~1.000 simultâneas)
   é o limite real, a **rede de segurança por IP** (transactional 300/min)
   e o teto por principal mantêm a concorrência agregada muito abaixo do
   ponto de inflexão. NÃO ajustar tetos para cima sem subir a camada de
   conexão (maxThreads/acceptCount do Tomcat ou proxy).

**Limitação de observabilidade:** CPU/RAM/Hikari/GC/Postgres não foram medidos
diretamente (actuator `/metrics` protegido por chave admin em prod — 401 sem
credencial). Inferidos indiretamente: latência dos aceitos estável + 0 5xx +
health UP + 0 OOM/restart nos logs. Critérios 10-12 (ledger/outbox/OOM sob
pico) permanecem com evidência indireta; a verificação direta exige expor as
métricas (chave admin) ou coletor de métricas.

## Flood por token master/admin (2026-08-07, follow-up #3 — concluído)

Teste dedicado do follow-up registrado na auditoria (critério 5 — o filtro é
path-agnóstico e estava coberto por design, sem teste dedicado). Executado
localmente (perfil dev, `NYXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS=5`,
rota `/api/v1/master/transactions` — SEM bucket de IP, mede só o limite por
principal):

1. Login do master → token.
2. **Flood de 20× no MESMO token** em `/api/v1/master/transactions` →
   **exatamente 5×200 + 15×429** `too_many_requests` (limite por principal
   masterId, 5/min reduzido para o teste).
3. **Isolamento por principal:** `GET /api/v1/admin/merchants?size=100` com a
   chave admin (bucket `admin`) → **200** — o flood do master NÃO afeta
   operações administrativas legítimas abaixo do teto.
4. **Recuperação:** após a janela (60s), o mesmo token volta a funcionar na
   rota master → **200**.

**Resultado: PASS.** O critério 5 da auditoria (429 por token em
admin/master + operações legítimas abaixo do teto) agora tem evidência
dedicada. Status dos follow-ups da Spec 58: benchmark Railway ✅ (seção
acima), flood master/admin ✅, re-execução da suíte E2E Playwright ✅.

## Re-execução da suíte E2E Playwright (2026-08-07, follow-up #2 — concluído)

Suíte **completa** (9 arquivos, 104 testes) re-executada com a app em perfil
dev e **rate limit nos valores default ativos** (mesmo setup do CI — sem
override de `NYXCASH_SECURITY_RATE_LIMIT_*`): **104/104 passed (5,7m)** —
auth 24, bots 2, **concurrency 7** (incl. lock pessimista: replay concorrente
→ 1 auditoria), merchant-journey 27, outbox-flow 1, portal 10, psp-flow 1,
reliability 1, security 31. **Zero 429** — os fluxos existentes usam poucas
requisições, abaixo de qualquer teto (critério 7 da auditoria com evidência
direta). **Follow-ups da Spec 58: 3/3 concluídos.**
