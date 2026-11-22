# Deploy no Railway (backend FoxCash + PostgreSQL)

Decisão de infraestrutura **2026-08-05** (ADR-011): o Railway é a plataforma
escolhida para **hospedar o backend** e o **banco PostgreSQL remoto** — no
lugar da VPS própria + systemd documentada em `deploy/README.md` (mantida como
alternativa).

## Topologia

```
Cliente ──HTTPS──► Edge do Railway (TLS, *.up.railway.app / domínio custom)
                       │  HTTP puro + X-Forwarded-Proto: https
                       ▼
              Backend FoxCash (app)  — porta $PORT (injetada pelo Railway)
                       │  JDBC (PostgreSQL 18 do Railway — Spec 132)
                       ▼
              Railway Postgres (plugin) — DATABASE_URL
```

- **TLS termina no edge** — o app NÃO configura keystore próprio (o perfil
  `railway` anula `server.ssl.*` e ativa `forward-headers-strategy=framework`;
  o `require-https` do prod redireciona HTTP→HTTPS sem loop).
- **Porta**: Railway injeta `$PORT` — o app escuta nela (`server.port=${PORT}`).

## Pré-requisitos

- Conta no [Railway](https://railway.app) (plano Hobby/Pro conforme uso).
- Repositório no GitHub (deploy automático) **ou** Railway CLI local:
  ```bash
  curl -fsSL https://railway.app/install.sh | sh
  railway login
  ```

## Passo a passo

### 1. Criar o projeto e o banco

1. **Railway dashboard → New Project → Empty Project** (ou conectar o repo do
   GitHub e criar direto).
2. **Add New → Database → PostgreSQL** (Railway Postgres). Anote o valor da
   variável `DATABASE_URL` gerada:
   `postgresql://USER:PASS@HOST:PORT/railway`

### 2. Configurar as variáveis de ambiente do serviço do app

No serviço do backend (variáveis): crie as seguintes (Reference a outra
variável com `${VAR}` — ex.: `DB_URL` referenciando o `DATABASE_URL` do
plugin):

| Variável | Valor | Obrigatória | Notas |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,railway` | ✅ | Edge TLS + $PORT |
| `DB_URL` | `jdbc:postgresql://HOST:PORT/railway` | ✅ | Railway não aceita `postgresql://` no driver JDBC (use `jdbc:postgresql://`) |
| `DB_USERNAME` | usuário do DATABASE_URL | ✅ | |
| `DB_PASSWORD` | senha do DATABASE_URL | ✅ | |
| `FOXCASH_CRYPTO_SECRET` | 32+ caracteres aleatórios | ✅ | Criptografia em repouso (fail-fast sem ela) |
| `FOXCASH_ADMIN_API_KEY` | chave admin (Spec 12) | ✅ | fail-fast sem ela |
| `FOXCASH_PSP_PROVIDER` | `pagarme`/`asaas` (ou `mock` só p/ piloto) | ✅ | sem valor o boot falha |
| `FOXCASH_PSP_PAYOUT_PROVIDER` | `pagarme`/`asaas` (ou `mock` p/ piloto) | ✅ | sem valor o boot falha |
| `FOXCASH_PSP_WEBHOOK_SECRET` | segredo de assinatura do webhook Pix | ✅ | |
| `FOXCASH_PSP_PAYOUT_WEBHOOK_SECRET` | segredo do webhook de saída | ✅ | |
| `FOXCASH_PSP_PAGARME_SECRET_KEY` | (se `pagarme`) | se provider | |
| `FOXCASH_PSP_PAYOUT_PAGARME_SECRET_KEY` | (se payout pagarme) | se provider | |
| `FOXCASH_PSP_PAYOUT_ASAAS_SECRET_KEY` | (se payout asaas) | se provider | |
| `FOXCASH_PSP_ASAAS_SECRET_KEY` | (se `asaas`) | se provider | Spec 92 — também usada pelo saldo master (GET /finance/balance) |
| `FOXCASH_PSP_SWEEP_ENABLED` | `true` para ativar o sweep (default `false`) | se sweep | Spec 92 — kill-switch; só roda quando ligado |
| `FOXCASH_PSP_SWEEP_CRON` | `0 0 6 * * *` (default, UTC) | se sweep | fora da janela de pico de saques |
| `FOXCASH_PSP_SWEEP_BUFFER_AMOUNT` | `0` (default) | se sweep | R$ retido no PSP p/ os saques dos lojistas (zero-balance estrito = 0) |
| `FOXCASH_PSP_SWEEP_DESTINATION_KEY` | chave Pix da conta blindada corporativa (EVP recomendada) | ✅ se sweep | fail-fast se sweep habilitado sem destino |
| `FOXCASH_PSP_SWEEP_EXPOSURE_ALERT` | `10000` (default) | opcional | limiar (R$) do alerta `SWEEP_EXPOSURE_HIGH` |
| `FOXCASH_PSP_MOCK_BALANCE` | `0` (default) | só dev/test | saldo fake da conta master (mock) |
| `FOXCASH_PSP_PAYOUT_ROUTING_ENABLED` | `false` (default) | opcional | Spec 92 — fallback de saque por prioridade |
| `FOXCASH_PSP_PAYOUT_ROUTING_PROVIDERS` | ex.: `pagarme,asaas` | se routing | ordem de prioridade; listado sem bean → fail-fast |
| `FOXCASH_MASTER_SEED_USERNAME`/`PASSWORD` | primeiro master | 1ª vez | seed só em tabela vazia |
| `FOXCASH_TRUST_PROXY` | `true` | ✅ | edge SOBRESCREVE X-Forwarded-For → rate limit vê o IP real |
| `FOXCASH_RATE_LIMIT_STORE` | `memory` (default) / `redis` | opcional | `redis` exige plugin Redis + `SPRING_DATA_REDIS_*` (ADR-010) |
| `FOXCASH_NOTIFICATION_ALLOW_LOOPBACK` | `false` | ✅ | SSRF (nunca `true` em prod) |
| `FOXCASH_RESET_TOKEN_DELIVERER` | `smtp` | ✅ | Spec 45 — `log` em prod é **rejeitado no boot** (fail-fast) |
| `FOXCASH_RESET_TOKEN_SMTP_HOST` | host SMTP | se e-mail | Sem valores o boot NÃO falha (falha logada na entrega) |
| `FOXCASH_RESET_TOKEN_SMTP_PORT`/`USERNAME`/`PASSWORD`/`FROM` | SMTP (default porta 587) | se e-mail | idem |
| `FOXCASH_PORTAL_BASE_URL` | URL pública do frontend (link de reset) | se e-mail | Sem valor a entrega loga erro |
| `FOXCASH_BOTS_WEBHOOK_BASE_URL` | `https://<host>` (ex.: `https://foxcash.example.com`) | bots Telegram | Spec 74 — base do webhook `/webhooks/telegram/{id}/{secret}`; vazio registra webhook p/ `localhost:8080` (quebra em prod) |
| `FOXCASH_BOTS_TELEGRAM_CDN_CHANNEL_ID` | ID de um canal/grupo onde o bot é admin (ex.: `-1001234567890`) | mídia de broadcast (Spec 79/D7) | Sem valor, o upload de mídia em transmissões Telegram falha com `Telegram: canal CDN nao configurado (foxcash.bots.telegram-cdn-channel-id)`. O bot do lojista precisa ser **admin** do canal — o upload envia o binário 1× e reusa o `file_id` nos disparos |
| `FOXCASH_MAIL_HEALTH_ENABLED` | `false` (default) / `true` | opcional | Spec 55 — SMTP opcional não derruba o health; `true` religa o indicador de mail |
| `FOXCASH_TRACING_SAMPLING_PROBABILITY` | `0.1` (default) | opcional | Spec 57 — fração de requisições amostradas para tracing |
| `FOXCASH_OTLP_ENDPOINT` | collector OTLP (ex.: Grafana Tempo/Jaeger) | opcional | Spec 57 — sem valor a app usa `http://localhost:4318/v1/traces` e **não derruba** (fail-open); `traceId`/`spanId` sempre nos logs estruturados |

> **Segredos** (`FOXCASH_CRYPTO_SECRET`, senhas, keys): o Railway criptografa
> variáveis como **Reference** ou use a UI (o valor fica oculto após salvar).
> Nunca commite segredos.

### 2b. Tuning de runtime (Spec 60 — Tomcat/Hikari/Heap)

O `Dockerfile` agora entra via `sh -c "java $JAVA_OPTS -jar app.jar"` — o teto
de heap é controlado pela env **sem rebuild**. Baselines (a calibrar no
benchmark k6 do Railway — ver Spec 60):

| Variável | Baseline | Notas |
|---|---|---|
| `JAVA_OPTS` | `-Xmx320m` | teto de heap explícito (heap observado ~100MB sob benchmark → folga). **ATENÇÃO (2026-08-08):** o `-XX:MaxMetaspaceSize=96m` documentado originalmente causa **`OutOfMemoryError: Metaspace`** no boot (Spring Boot/Hibernate/Lettuce precisam de mais) — NÃO usar cap de metaspace. Instância Railway observada ~1GB. Meta: **zero OOM, zero GC thrashing** sob pico. Ajustar conforme `jvm.memory.used` |
| `FOXCASH_TOMCAT_MAX_CONNECTIONS` | `1000` (default 8192) | anti-DoS de sockets — calibrar com o benchmark |
| `FOXCASH_TOMCAT_ACCEPT_COUNT` | `200` | fila de aceite (backlog) |
| `FOXCASH_TOMCAT_CONNECTION_TIMEOUT` | `10000` ms | descarta slow-connectors |
| `FOXCASH_TOMCAT_KEEP_ALIVE_TIMEOUT` | `30000` ms | keep-alive ocioso encerra |
| `FOXCASH_TOMCAT_MAX_KEEP_ALIVE_REQUESTS` | `100` | recicla conexões |
| `FOXCASH_DB_POOL_SIZE` | `20` | Hikari — pool **por réplica**; total N×pool ≤ Postgres max_connections (500) — regra de dimensionamento no §pool desta página (Spec 133) |
| `FOXCASH_SECURITY_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | `120`/min/IP | Spec 59 — teto do webhook público (relaxed binding). Calibrar para cortar com 429 **antes** da saturação, SEM bloquear a reentrega do PSP (minutos) |

**Alertas (Prometheus/Grafana, actuator `/actuator/prometheus`):**
- `hikaricp_connection_timeout_total` — **exhaustion do pool**: se > 0 sob
  pico, o pool está baixo demais (ou o Postgres saturado).
- `jvm.memory.used` vs teto — GC thrashing/OOM iminente.
- `http_server_requests_seconds_count{status="500"}` — saturação antes do 429.
- `hikaricp_connections_active` vs `maximum-pool-size` — folga.

### 2c. Sweep do saldo do PSP (Spec 92 — proteção contra freeze)

O job de sweep transfere o **excedente** (Disponível − buffer) da conta master
do adquirente para a **conta blindada corporativa**, retendo um buffer para os
saques dos lojistas. Setup ponta-a-ponta:

1. **Criar a conta blindada (destino)** no banco da empresa (não é a conta do
   adquirente) e gerar uma **chave Pix EVP** (Escrow/Valor Protegido) nela —
   `FOXCASH_PSP_SWEEP_DESTINATION_KEY` recebe essa chave. A EVP tem **saque
   bloqueado** por padrão → o dinheiro fica fora de alcance de um freeze no PSP
   e de uma falha de permissão no saque.
2. **Credencial payout-scoped (isolação de credenciais):** a chave de
   **COBRANCA** (`FOXCASH_PSP_*_SECRET_KEY`) **nunca** pode ser a chave de
   **SAQUE/sweep** (`FOXCASH_PSP_PAYOUT_*_SECRET_KEY`) do MESMO adquirente — o
   boot falha se forem iguais (fail-fast, qualquer perfil). No adquirente,
   crie uma credencial **payout-scoped** (com permissão apenas de
   transferência/saque, sem permissão de cobrança e sem acesso à API de
   saldo).
3. **IP whitelist (egresso do Railway):** no painel do adquirente, restrinja a
   credencial payout-scoped aos **IPs de egresso do Railway** do serviço (em
   `Settings → Networking → Egress IPs`; fixos no plano de produção). Se um
   atacante vazar a chave de cobrança, ela não move saldo (fail-fast de
   isolação) e a payout-scoped só aceita tráfego dos IPs do Railway.
4. **Buffer e exposição:** `FOXCASH_PSP_SWEEP_BUFFER_AMOUNT` = valor que deve
   permanecer no PSP para os saques em trânsito; `FOXCASH_PSP_SWEEP_EXPOSURE_ALERT`
   = limiar de exposição (Disponível − buffer) que dispara o alerta
   `SWEEP_EXPOSURE_HIGH` (webhook de operador). Zero-balance estrito = buffer 0.
5. **Ativação:** `FOXCASH_PSP_SWEEP_ENABLED=true` + `FOXCASH_PSP_SWEEP_DESTINATION_KEY`
   (fail-fast se habilitado sem destino). O job roda com ShedLock (uma
   instância por vez) e é idempotente por dia (UUID determinístico
   `sweep:<data>` — replay não duplica). Falha → job FAILED + alerta
   `SWEEP_FAILED`.

### 2d. Segurança de runtime (Spec 131 — sem senha default, actuator mínimo, headers)

O app **não usa** `UserDetailsService` do Spring Security — a autenticação é por
filters customizados (`ApiKeyAuthFilter`/`MasterAuthFilter`/`MerchantPortalAuthFilter`).
Por isso o `application.yml` **exclui** o auto-config
`UserDetailsServiceAutoConfiguration`: sem a exclusão, o Boot detecta o fallback
default e loga `Using generated security password: <uuid>` no boot de produção
(superfície de Basic auth com user default que não existe no app — achado do due
diligence, 2 ocorrências no deploy `94861317`). **Não defina** `spring.security.user.*`:
nenhum user default existe; a exclusão é a decisão (Spec 131 T0, fix `caace79`).

Superfície mínima (verificada por `SecurityRuntimeSecurityTest`, grupo context):

- `/actuator/health` (+ `/health/**`) **público** — liveness/readiness do
  orquestrador; `show-details: when_authorized` (nunca em claro sem auth).
- `/actuator/**` demais → **ROLE_ADMIN** (`X-Api-Key` da chave admin); exposição
  web limitada a `health,info,prometheus,metrics` (`application.yml`).
- Headers de segurança nas respostas: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Content-Security-Policy` (`default-src 'none'`; com
  SPA servida, permite `'self'` + estilos inline do Vite), `Referrer-Policy:
  no-referrer`, `Cache-Control` (no-store), e **HSTS** quando `require-https=true`.
- Logs sanitizados pelo `SensitiveDataLoggingFilter` (`***` em apiKey/password/
  token/secret/CPF/CNPJ) — coberto por teste de regressão (`SensitiveDataLoggingFilterTest`).

### 3. Deploy

**Via GitHub (recomendado):** conecte o repositório no serviço → o Railway
detecta o **`Dockerfile`** (JDK 21 explícito: `maven:3.9-eclipse-temurin-21`
no build → `eclipse-temurin:21-jre` no runtime) e sobe o fat-jar
(`java -jar app.jar`). O `railway.json` fixa o builder `DOCKERFILE`.

**Via CLI:**
```bash
cd FoxCash
railway link   # seleciona projeto/serviço
railway up
```

No primeiro boot o **Flyway aplica as migrations** (V1→V75) automaticamente.

> **Versões (Spec 132):** o Postgres do Railway roda **18.x**; o `pom.xml` usa
> **Flyway 12.11.0** via override `flyway.version` (o BOM do Boot 3.5.16 fixa
> 11.7.2, que só testa até PG 17 e logaria `Flyway upgrade recommended`). O boot
> valida as 64 migrations aplicadas sem warning — verificado contra PostgreSQL
> 18.4 local e coberto no CI pelo job `flyway:validate`/`flyway-coldstart`.

### 4. Verificação

1. Abra o log do serviço: deve aparecer `Started FoxCashApplication` e
   `Successfully validated 64 migrations` (sem `Flyway upgrade recommended`).
2. Smoke:
   ```bash
   BASE=https://<seu-serviço>.up.railway.app/api/v1
   curl -s -X POST $BASE/merchants -H 'Content-Type: application/json' \
     -d '{"name":"Smoke Railway","email":"smoke@railway.dev","password":"Senha-123"}'
   # esperado 201 com apiKey
   ```
3. Confirme HTTPS e redirect: `curl -sI http://<serviço>.up.railway.app/` →
   `301/308` para `https://` (sem loop).

### 5. (Opcional) Domínio custom

Settings do serviço → **Networking → Generate Domain** (`.up.railway.app`) ou
aponte seu domínio (CNAME) → **Custom Domain**. O TLS é automático.

### 6. Backups

Railway Postgres oferece **Backups** (snapshots) — habilite com retenção
conforme necessidade. Para dump manual:
```bash
railway run -- pg_dump --dbname=$DATABASE_URL -F c -f backup.dump
```

**Backup automático 6/6h (Spec 96 + RPO 6/6h da Spec 135)** — duas opções:

- **Cron do host (VPS/Windows):** `./ops/install-backup-cron.sh install` (ver
  `ops/backup-restore.md`) — para operadores com acesso ao host.
- **Serviço `backup-cron` no Railway:** o diretório `ops/` tem um `Dockerfile`
  (`postgres:18-alpine` — pg_dump 18 = versão do servidor Railway; inclui o
  `rclone` para replicação externa) + `backup-cron-entrypoint.sh` (interpreta
  `DATABASE_URL` → `DB_*`, roda `backup.sh` a cada **6h** na rede privada — RPO
  ≤ 4h —, falha não mata o loop). Deploy:

  ```bash
  cd ops
  railway up --detach   # serviço 'backup-cron' no MESMO projeto (rede privada)
  railway volume add --service backup-cron --mount-path /backups   # durabilidade
  ```

  O backup fica em `/backups` (volatile sem volume — anexe o volume acima). Logs
  no Railway Logs / log drain (§7). Runbook completo: `ops/backup-restore.md`.

**Replicação fora da região (Spec 135/T2.2 — DR):** com `R2_BUCKET` definido no
serviço `backup-cron`, o `backup.sh` copia o dump (via `ops/backup-upload.sh` +
rclone) para um bucket S3-compatível externo **multi-região** (padrão: Cloudflare
R2 — egress grátis; o domínio já está no Cloudflare, Spec 63). Sem `R2_BUCKET`,
o backup é só local (comportamento anterior). Ativação passo a passo:
`ops/backup-restore.md` → "Backup fora da região".

### 6.1 Multi-região (Spec 135/T3 — avaliação 2026-08-17)

**Estado atual:** tudo em **sfo** (FoxCash 2 réplicas, Postgres, backup-cron,
operator-bot — projeto `foxcash-prod`). O Postgres é o único estado
persistente (Spec 61) — single-region.

**Avaliação (com evidência):**

| Aspecto | Resultado |
|---|---|
| **App em 2+ regiões** | **Viável** — a app é stateless (Spec 61, 2 réplicas HA). Um 2º serviço Railway em outra região (ex.: `us-east-1`/`eu-west-1`) com o mesmo image, réplicas ≥ 2 e roteamento via **Cloudflare Load Balancing** (Spec 63/ADR-014) funciona sem mudança de código. |
| **Postgres multi-região** | **NÃO suportado no Railway** (managed Postgres é single-region; réplica de leitura multi-região/cross-region replication não é oferta geral — verificada a documentação/roadmap do Railway 2026-08-17). O Postgres não "vira standby" em outra região no Railway. |
| **Latência região secundária → primário** | **MEDIDA ao vivo (T3.1, 2026-08-17)** — rede privada `postgres.railway.internal` **funciona cross-região** (resolve/conecta de us-east — corrige a suspeita da Spec 62); o custo é latência: `SELECT 1` conexão nova **~443 ms** (us-east) vs ~36-40 ms (sfo); warm/pool **~61 ms** (us-east) vs ~2 ms (sfo). Detalhe abaixo. |
| **Caminho para DR real** | O que cumpre a promessa de due diligence hoje: **backup replicado fora da região (T2.2, bucket multi-região)** + restore provado (C1, DR drill 0.646s) + redeploy em minutos (Spec 61 zero-downtime). |

**Medição ao vivo (T3.1, 2026-08-17)** — probe `probe/` (postgres:18-alpine +
`probe.sh`, serviço descartável `dr-probe` criado e **deletado** após a medição;
re-produzir: `railway add --service dr-probe` → `railway up ./probe --path-as-root
--service dr-probe` → `railway service scale --service dr-probe us-east=1` → logs):

| Métrica | sfo (mesma região) | us-east (cross-região) | Penalty |
|---|---|---|---|
| `SELECT 1` conexão nova (psql -c) | ~36–40 ms | **~443 ms** | ~11x |
| `SELECT 1` warm, 1 conexão reusada (\timing ≈ pool Hikari) | ~2 ms | **~61 ms** | +59 ms/query |

**Leitura dos números:** o custo **steady-state por query** com pool (Hikari, Spec
60) é **~+59 ms** na 2ª região → no caminho do charge (5-6 queries pós-Spec 65)
seriam **+300-360 ms/request** de latência adicional — relevante para uma API de
pagamento. A conexão fria (~443 ms) só pesa em warm-up do pool/failover. Para o
**failover regional automático** o Postgres do Railway (single-region) não tem
standby cross-região — exigiria infra híbrida (Aurora Global/Cloud SQL).

**Decisão (T3.2):** **não forçar active-active multi-região agora.** Justificativa
com evidência: (1) o Postgres Railway não suporta multi-região — a app em 2ª
região adicionaria latência transacional (caminho crítico do charge, Spec 65) em
troca de benefício que **o backup externo + redeploy rápido já entrega** (RPO ≤
4h, RTO = provisionamento + restore); (2) multi-região de Postgres exigiria
infra híbrida (RDS Aurora Global/Cloud SQL etc. — fora do escopo, ADR-013
mantém Postgres como alvo). **Alternativa documentada:** se o negócio exigir
failover regional automático, migrar o Postgres para provedor com multi-AZ/global
(avaliação futura com evidência de necessidade). Runbook de failover: passos 1-6
em `ops/backup-restore.md` → "Disaster Recovery".

### 7. Log drain (opcional — Spec 95)

Para agregação externa de logs (investigação pós-incidente além da janela da
UI) há **2 opções** (escolha **uma** — ativar as duas duplica eventos; detalhes
em `ops/log-drain.md`):

- **A — Log drain da plataforma:** Railway → **Project → Observability → Log
  drain** → destino **Axiom** ou **Loki** (free tier). A credencial fica no
  Railway, nunca no repo — nenhuma env var nova no código.
- **B — Appender Axiom direto (código):** o app envia o JSON estruturado
  direto ao Axiom (fail-open). Adicione a env var **`AXIOM_API_TOKEN`**
  (dataset `FoxCash`; região → `api.axiom.co`/`eu.axiom.co` no
  `logback-spring.xml`).

Os logs do app **já saem estruturados em prod**
(`ts/level/logger/requestId/traceId/spanId/msg` — `logback-spring.xml`) e
**mascarados** (SensitiveDataLoggingFilter — sem senha/token/segredo em claro).

> Busca por `X-Request-Id` e correlação com `platform_activity_log`/métricas:
> ver o runbook **`ops/log-drain.md`**.

## Escala, alta disponibilidade e jobs (Spec 61)

- **ShedLock** (faturas, reconciliação, antifraude, webhooks): usa o
  PostgreSQL — **um único app replicado** continua com execução única por job
  (lock na tabela `shedlock`). Pode escalar o serviço com segurança.
- **Rate limit**: default `memory` é **por instância** (ADR-010). Com mais de
  uma réplica e desejo de limite agregado, use `FOXCASH_RATE_LIMIT_STORE=redis` +
  plugin Redis (fail-open documentado).

### HA multi-instância (Spec 61 — escalabilidade horizontal)

O `railway.json` define **2 réplicas** + healthcheck em
`/actuator/health/readiness` (sem auth — o SecurityConfig libera
`/actuator/health/**`): a instância só entra no balanço quando pronta
(Flyway/DB OK). Com `replicas > 1`, o Railway faz rollout sem downtime.

**Obrigatório em multi-instância (env vars):**

| Variável | Valor | Por quê |
|---|---|---|
| `FOXCASH_RATE_LIMIT_STORE` | `redis` | contadores agregados entre réplicas (ADR-010); sem isso o rate limit é por instância (N× o teto) |
| `SPRING_DATA_REDIS_HOST`/`SPRING_DATA_REDIS_PORT` | plugin Redis do Railway | conexão do `RedisRateLimitStore` (fail-open se cair) |
| `FOXCASH_SHUTDOWN_TIMEOUT` | `30s` (default) | dreno do shutdown graceful (SIGTERM no deploy) — requisições em voo completam antes do encerramento |

**Shutdown graceful:** `server.shutdown=graceful` (Spec 61) — no rollout o
Tomcat para de aceitar novas conexões e drena as em voo até
`spring.lifecycle.timeout-per-shutdown-phase`; o scheduler já encerra jobs
antes de parar (`waitForTasksToCompleteOnShutdown`, Spec 53).

**Dimensionamento do pool de DB por réplica:** `FOXCASH_DB_POOL_SIZE` é o pool
**por instância** (Spec 60 — `hikari.maximum-pool-size` no `application.yml`):
com N réplicas, o total consumido é N × pool e deve respeitar o
`max_connections` do Postgres Railway (**500** — ADR-013/evidência F9 Spec 62).
**Evidência do limite (Spec 62 §11.11, 2026-08-10):** com pool 100/réplica,
8 réplicas desejam 800 conexões > 500 → `too many clients already` — o
scale-out útil para em ~4-8 réplicas (~1.250-1.554/s) e **degrada em 16**
(sale_ok 94-97%, p95 charge ~10-19s). **Regra de dimensionamento (Spec 133):**
`pool ≤ floor(500 / réplicas)` com margem (ex.: 8 réplicas → 60 × 8 = 480 <
500; 16 réplicas → 30 × 16 = 480). Valor calibrado no benchmark da Spec 62 F1:
pool 20/réplica sem contenção (acquire máx 0,6 ms). O **novo número de
capacidade medido** (pós-benchmark do Spec 133) é registrado aqui quando
executado — nunca estimado (diretriz 2026-08-08).

**Rollout/rollback:**
- **Rollout:** `git push` → Railway builda e faz rollout das réplicas
  (o healthcheck valida readiness antes de trocar o tráfego; o dreno do
  graceful shutdown completa requisições em voo — zero downtime).
- **Rollback:** Settings → Deployments → redeploy da versão anterior.
- **Verificação pós-deploy:** `/actuator/health` 200 UP (liveness/readiness
  por réplica); `/actuator/prometheus` com `hikaricp_connections_active` e
  `jvm.memory.used` com folga; logs com `Commencing graceful shutdown` no
  rollout.

**Nota:** o Postgres Railway é o ponto único de HA (replicação/failover de
banco é plano/infra gerenciada do Railway — fora da Spec 61).

## Checklist de integridade pré-deploy (L19)

O `mvn test` unitário **não prova o runtime real** (não sobe a app nem abre
conexão JDBC) — um artefato corrompido (jar do `.m2` ou `.class` do `target/`)
passa verde e explode no boot. Rodar, **em ordem**, antes de todo deploy:

1. **`unzip -t` nos jars críticos do `.m2`** (driver JDBC e starters usados no
   boot): `unzip -t ~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar`
   — qualquer "error was detected" = jar corrompido → `rm -rf` do diretório do
   artefato + `mvn dependency:get -Dartifact=<g:a:v>` (re-baixar) + repetir o
   `unzip -t`.
2. **`mvn -o clean test`** (o `clean` elimina `.class` corrompido do `target/`
   que o build incremental preserva — L18).
3. **Boot real local**: `mvn -o spring-boot:run` com o perfil dev e conferir
   `curl http://localhost:8080/actuator/health` = 200 (pega `ClassFormatError`/
   `ZipException`/`NoClassDefFoundError` de driver/lib que só aparecem na
   conexão real — Flyway/JPA). Em CI isso já é coberto pelos jobs
   `flyway:validate`/`flyway-coldstart` e `-Pcontext-tests` (Spec 68).
4. **`mvn -o flyway:validate`** (conecta no Postgres e carrega o driver) —
   falha de classe do driver aqui = corrupção no `.m2`.

Sintoma `ClassFormatError: Invalid constant pool index` no boot = jar do `.m2`
corrompido (classe de pacote de lib, ex.: `org.postgresql.*`); sintoma
`Unresolved compilation problem` = `.class` do próprio projeto no `target/`
(L18). Fixes diferentes: re-baixar o jar vs `mvn clean`. Detalhes em L19.

## Troubleshooting

| Sintoma | Causa provável | Ação |
|---|---|---|
| Boot falha `Could not resolve placeholder 'DB_URL'` | env não criada | Criar `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` (apontando p/ o DATABASE_URL do plugin) |
| Boot falha `foxcash.crypto.secret e obrigatorio` | `FOXCASH_CRYPTO_SECRET` ausente | Criar a variável (32+ chars) |
| Loop de redirect HTTP↔HTTPS | `SPRING_PROFILES_ACTIVE` sem `railway` | Usar `prod,railway` (forward-headers-strategy) |
| App sobe mas 404/porta errada | `server.port` fixo | Usar o perfil `railway` (`${PORT}`) |
| 401 em tudo / chave admin | `FOXCASH_ADMIN_API_KEY` divergente | Conferir a variável |
| Webhook 401 | `FOXCASH_PSP_WEBHOOK_SECRET` divergente do sandbox | Conferir assinatura |
| Deploy FAILED `replicas never became healthy` / `Healthcheck failed!` | healthcheck do Railway sonda via **HTTP puro** e o `requiresChannel(anyRequest().requiresSecure())` do prod responde **302** | Corrigido no código (fix 744c1f0): `/actuator/health/**` eximido com `ChannelDecisionManagerImpl.ANY_CHANNEL` — o redirect HTTPS continua valendo para a API |
| Rate limit **não dispara** (fail-open) com `FOXCASH_RATE_LIMIT_STORE=redis` | Reference do Railway não resolveu — a sintaxe é **`${{Service.VAR}}`** (chaves duplas); `${Service.VAR}` fica como string literal e o RedisRateLimitStore cai em fail-open | Setar `SPRING_DATA_REDIS_PASSWORD=${{Redis.REDIS_PASSWORD}}` e verificar com `railway run -- printenv SPRING_DATA_REDIS_PASSWORD` |

## Alternativa (VPS própria)

O fluxo original (systemd + keystore TLS na 8443, `deploy/README.md` +
`deploy/foxcash.service`) continua válido para quem preferir VPS — nesse caso
**NÃO** use o perfil `railway` (`SPRING_PROFILES_ACTIVE=prod` apenas) e forneça
`FOXCASH_SSL_KEYSTORE`/`FOXCASH_SSL_KEYSTORE_PASSWORD`.
