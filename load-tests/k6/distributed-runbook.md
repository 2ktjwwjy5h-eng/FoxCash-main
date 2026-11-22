# Runbook — Geradores distribuídos (Fase 2: 600→750/s)

Extensão do `capacity-stages.js` para **N geradores k6 rodando no Railway**
(rede privada), contornando o limite do edge por-IP que travou medições limpas
acima de ~400/s na Etapa 2 (docs §11.8).

## Arquitetura

```
loadgen (Railway, grafana/k6, N réplicas)
   │  HTTP (rede privada, sem edge público)
   ▼
foxcash.railway.internal:PORT  (FoxCash, 2 réplicas)
   │
   ▼
Postgres Railway
```

- **Rede privada** (`http://foxcash.railway.internal:8080`): bypassa o edge público
  (sem limite de conexões por-IP, sem TLS no caminho). O rate limit é o da app
  (elevado durante o teste). **A porta do app na rede privada é `${PORT:8080}`
  (perfil prod,railway) — `foxcash.railway.internal:80` NÃO funciona (connection
  refused).**
- **Rate total = N × STAGE**: cada réplica roda o script inteiro com seu próprio
  rate (`constant-arrival-rate`). Ex.: 600/s = 2 réplicas × STAGE=300.
- **Idempotência**: o script gera `keyBase` único por réplica (`INSTANCE` env ou
  sufixo aleatório no setup) — sem colisão de `idempotencyKey` entre geradores.

## Pré-requisitos

- CLI Railway autenticado (`railway whoami`).
- Pasta `loadgen/` autocontida (Dockerfile + `capacity-stages.js` + `railway.json`
  com `numReplicas`) — deploy via `railway up ./loadgen --path-as-root --service
  loadgen`. **Não usar `railway deployment redeploy` para o loadgen**: ele reusa o
  config do deployment antigo (start command / replicas) e ignora mudanças de
  `serviceInstance`.

## Passo a passo

### 1. Relaxar produção temporariamente (2 vars + 1 redeploy)

```bash
# HTTPS exigido pelo perfil prod (redirect HTTP→HTTPS) — a rede privada é HTTP:
railway variable set -s FoxCash --skip-deploys FOXCASH_SECURITY_REQUIRE_HTTPS=false
# Rate limit elevado (idem fases anteriores):
railway variable set -s FoxCash --skip-deploys \
  FOXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS=10000000 \
  FOXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS=10000000 \
  FOXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS=10000000 \
  FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS=10000000
railway deployment redeploy -s FoxCash -y
```

> ⚠️ `require-https=false` é relaxamento de segurança — só durante o teste,
> restaurado no passo 5 (obrigatório).

### 2. Configurar o serviço loadgen

- **Source**: pasta local `loadgen/` via `railway up ./loadgen --path-as-root
  --service loadgen -y -d`. O Dockerfile bakeia o `capacity-stages.js` e o
  `railway.json` define `numReplicas`. **⚠️ Depois de mudar o config do serviço
  (start command / replicas / env), SEMPRE deploy via `railway up` — o
  `railway deployment redeploy` reusa o config antigo do deployment e ignora a
  mudança.**
- **Replicas**: via `loadgen/railway.json` (`deploy.numReplicas`), N conforme a
  fase (tabela abaixo). (O `serviceInstanceUpdate` da API não aceita `numReplicas:
  0`; para parar, deletar o serviço.)
- **Env do loadgen** (`railway variable set -s loadgen ...`):

| Variável | Fase 600/s | Fase 750/s |
|---|---|---|
| `BASE_URL` | `http://foxcash.railway.internal:8080` | idem |
| `STAGE` | `300` (×2 réplicas) | `250` (×3 réplicas) |
| `POOL` | `30` | `25` |
| `MAX_VUS` | `600` | `500` |
| `DURATION` | `45s` | idem |
| `WEBHOOK_SECRET` | valor de `FOXCASH_PSP_WEBHOOK_SECRET` (Railway) | idem |
| `INSTANCE` | opcional (sufixo por réplica; sem ele o script randomiza) | idem |

Aplicar via UI do Railway (Settings → Variables) ou `railway variable set`.

### 3. Rodar a fase

```bash
railway up ./loadgen --path-as-root --service loadgen --detach --json -y -m "fase NxSTAGE"
# Acompanhar (o summary k6 sai no log DO DEPLOYMENT, não no --service):
railway logs <deployment_id> --service loadgen   # sale_ok / 5xx / latência / dropped
# Monitorar a carga no banco (total/max created_at):
node load-tests/baseline-pre-teste.cjs   # (ou query direta no túnel)
```

### 4. Gate pós-carga (mesmos critérios da Fase 2)

1. **Outbox drenando**: `SENT` subindo / `PENDING` caindo (ver `baseline-pré-teste.cjs`).
2. **Integridade**: dups `idempotency_key` = 0, `reconciliation_issues` = 0,
   wallet×ledger = 0, deadlocks = 0.
3. **5xx**: `sale_5xx` ≈ 0 (falhas de gerador = conexão, não app).

### 5. Restauração obrigatória (após TODAS as fases)

```bash
railway variable set -s FoxCash --skip-deploys FOXCASH_SECURITY_REQUIRE_HTTPS=true
railway variable set -s FoxCash --skip-deploys \
  FOXCASH_SECURITY_RATE_LIMIT_MAX_REQUESTS=20 \
  FOXCASH_SECURITY_RATE_LIMIT_AUTHENTICATED_MAX_REQUESTS=120 \
  FOXCASH_SECURITY_RATE_LIMIT_TRANSACTIONAL_MAX_REQUESTS=300 \
  FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS=120
railway deployment redeploy -s FoxCash -y
# Parar o loadgen (não deixar gerador ativo em produção):
#  Railway UI → loadgen → Settings → Replicas = 0 (ou deletar o serviço)
```

### 6. Verificação pós-restauração

- Health UP, 2/2 réplicas.
- Rate limit 20/120/300/120 aplicado (probe com 429 em rajada).
- Outbox drenando o backlog com o rate limit restaurado.
- Integridade 0/0/0/0.

## Tabela de fases

| Alvo | Réplicas | STAGE/réplica | Pool/réplica | MAX_VUS/réplica |
|---|---|---|---|---|
| 600/s | 2 | 300 | 30 | 600 |
| 750/s | 3 | 250 | 25 | 500 |

## Limitações conhecidas

- `setup()` cria o pool de lojistas por réplica (N × POOL cadastros por fase) —
  custo aceito (cadastro público, ~0,7s cada, timeout do setup 180s).
- O rate por réplica é independente (não há orquestração de chegadas entre
  réplicas) — para um perfil de chegada agregado exato seria preciso k6 Cloud
  ou orquestração externa. Para teto de integridade/latência isso não altera a
  conclusão.
- `require-https=false` só vale para o teste; o tráfego público continua via
  edge com HTTPS (não afetado).

## Lições aprendidas (Fase 2B executada 2026-08-09)

1. **`sh -c "sleep infinity"` como start command do serviço**: serviço criado como
   imagem (sem Dockerfile) ganha esse start command na UI — o container NUNCA roda
   o k6 (fica dormindo; `railway logs` mostra só "Starting Container"). Limpar via
   API: `serviceInstanceUpdate(input:{startCommand:""})`. **Depois, deploy via
   `railway up`** (o redeploy reusa o config antigo).
2. **Porta da rede privada**: `foxcash.railway.internal:80` → connection refused.
   O app escuta em `${PORT:8080}` — usar `http://foxcash.railway.internal:8080`.
3. **`railway deployment redeploy` ≠ aplicar config**: redeploy reusa o config do
   deployment anterior (start command, replicas). Só `railway up` (ou novo deploy
   da UI) aplica o `serviceInstance` atual. Para mudar replicas de forma
   reprodutível, manter `loadgen/railway.json` com `deploy.numReplicas`.
4. **Diagnóstico de "container não gera tráfego"**: o summary do k6 só aparece no
   log DO DEPLOYMENT (`railway logs <deployment_id> --service loadgen`), não no
   `railway logs --service`. Para saber se o k6 rodou, olhar o banco (merchants
   `Cap *` novos) e o log do deployment.
5. **`res.body.slice` no setup**: falha de conexão (ex.: porta errada) deixa o
   `res.body` null → TypeError mascarava o connection refused. O handler agora usa
   `(res.body || res.error || '')`.
6. **Resultado da fase**: o teto real sustentado da app (2 réplicas) é ~470-480
   vendas/s agregadas — chegada de 600/s ou 750/s não aumenta o throughput (a
   latência do charge degrada p95 ~5,5s e o gerador estoura o VU cap). Ver
   `docs/capacity-report.md` §11.9.
