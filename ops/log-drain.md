# Log drain e consulta de logs — runbook (Spec 95)

> Fecha o critério 8 da Spec 64 ("logs investigáveis"): onde os logs vivem, como
> habilitar a **agregação externa** (log drain) e como **investigar por
> `X-Request-Id`** cruzando com `platform_activity_log` (SQL) e métricas
> (`/actuator/prometheus`). O item "Logtail" da auditoria original é **N/A** na
> stack Java/Spring + Railway — o destino recomendado é **Axiom** ou **Loki**
> (free tier), compatíveis com o formato estruturado abaixo.

## 1. Onde os logs vivem

| Fonte | Onde | Janela/limite |
|---|---|---|
| App (Spring Boot) | Railway → serviço → aba **Logs** (UI) ou `railway logs` (CLI) | Retenção limitada da plataforma (free tier) — consulte a doc oficial do Railway para o valor vigente |
| Trilha de auditoria | `platform_activity_log` no Postgres (Spec 41 — append-only) | Indefinida no banco; job de retenção opt-in (Spec 95) expurga > `activity-log.retention-days` quando habilitado |
| Métricas | `/actuator/prometheus` (Spec 53) — scrape Prometheus/Grafana | Série de tempo conforme scrape |

O log estruturado de produção (perfil `prod`) tem **uma linha por evento**:

```
ts=2026-08-15T01:00:00.123Z level=INFO  logger=c.a.i.rest.PortalMfaController requestId=abc-123 traceId=... spanId=... msg=...
```

Campos: `ts` (ISO-8601), `level`, `logger`, `requestId` (MDC — `RequestIdFilter`),
`traceId`/`spanId` (Micrometer Tracing, Spec 57), `msg` (quebras de linha
normalizadas). Parseável por Loki/Datadog/ELK sem transformação.

## 2. Habilitar a agregação — 2 opções

Há **duas** formas de levar os logs para o agregador. Recomenda-se escolher **uma**
(ativar as duas duplica eventos; ver §2.3).

### 2.1 Opção A — Log drain da plataforma Railway (config operacional)

1. Crie a conta no agregador (ex.: **Axiom** free tier — ingress endpoint +
   API token; ou **Loki** via Grafana Cloud — endpoint push + credenciais).
2. Railway → **Project → Observability → Log drain** → **Add drain**.
3. Preencha o endpoint de ingestão e a credencial do agregador. **A credencial
   fica no Railway — nunca no repo/git**; o drain é config da plataforma, não da
   aplicação (nenhuma env var nova no código).
4. Valide: gere uma request qualquer e confirme o evento no agregador (filtrando
   pelo `requestId` da resposta).

### 2.2 Opção B — Appender Axiom direto (código — Spec 95)

O backend tem o **`AxiomHttpAppender`** (`com.foxcash.infrastructure.logging`):
POST assíncrono (fila limitada 2000, batch 50) do JSON estruturado
(`LogstashEncoder` com MDC `requestId`/`traceId`/`spanId`) direto para o ingest
do Axiom. **Fail-open**: sem token/config o appender fica desligado e a app sobe
normalmente; Axiom fora/fila cheia → descarta com WARN (nunca bloqueia o
request). Habilitado no profile `prod` via `logback-spring.xml`.

1. **Crie o dataset no dashboard do Axiom** (app.axiom.co → Datasets → New
   Dataset). O nome DEVE bater com o `url` do `logback-spring.xml`:
   **`foxcash` (minúsculo)** — criado em 2026-08-20. Senão o ingest responde
   **404 `dataset not found`** (dataset inexistente) ou **403** (token escopado
   a outro dataset). O token de ingest **não** tem permissão de criar dataset
   (403) — é passo manual do dono da conta.
2. No Railway, adicione/atualize a env var **`AXIOM_API_TOKEN`** com o token de
   ingest **desse dataset** (o token do dashboard é escopado: funciona SÓ no
   dataset para o qual foi gerado). **Nunca no repo/git**.
3. Confira o `url` no `logback-spring.xml`: default
   `https://api.axiom.co/api/v1/datasets/foxcash/ingest`. Se a conta Axiom for
   fora da região US, troque `api.axiom.co` por `eu.axiom.co`; se o dataset
   tiver outro nome, ajuste o path (e o token).
4. Deploy + valide: os eventos aparecem no dataset `foxcash` com
   `service=foxcash` e o `requestId` do MDC. Sem o dataset/token corretos, o
   appender loga WARN a cada lote (polui o próprio console estruturado) —
   inofensivo (fail-open), mas o drain fica morto.

### 2.3 Escolha e duplicação

Ativar **A** e **B** juntos envia cada evento **2×** ao Axiom. Escolha **uma**:
- **A** se você quer o drain da plataforma (cobre também logs de redeploy/CLI);
- **B** se você prefere o appender (controle do payload, independente do
  Railway, funciona com qualquer agregador compatível com o formato).

**Estado atual (2026-08-20):** **B ativo** (appender no backend, corrigido —
dataset `foxcash`). O **Vector Log Forwarder** (opção A, `vector-log-forwarder/`)
foi **consertado e está Online** (deploy `8fb31894`, escutando em
`0.0.0.0:8080`) — mas o log drain do Railway ainda NÃO aponta para ele, então
não há duplicação. Para ativar A no lugar de B:

1. **Desligue B**: `AxiomHttpAppender` está no profile `prod` do
   `logback-spring.xml` — remova o `<appender-ref ref="AXIOM"/>` (ou remova o
   bloco do appender) e faça deploy.
2. **Configure o drain no Railway**: Project → **Observability → Log drain** →
   add drain → URL
   `http://vector-log-forwarder.railway.internal:8080` (privado, porta 8080 —
   o source `http_server` do Vector).
3. **Redeploy do Vector** só se o config mudar (`vector-log-forwarder/`):
   ```cmd
   railway up --path-as-root -s "Vector Log Forwarder" -e production ./vector-log-forwarder
   ```
   (Fonte local obrigatória — o serviço estava conectado ao repo GitHub e
   buildava o backend Java; `--path-as-root` força o Dockerfile do diretório.)

> **Privacidade:** os logs do app já passam pelo `SensitiveDataLoggingFilter` —
> senha/token/segredo/documento/e-mail **nunca** em claro (Specs 41/64). Não
> adicione dados sensíveis em mensagens de log futuras; se precisar de contexto,
> use o `id`/hash e cruze no banco.

## 3. Investigar por `X-Request-Id`

1. **Capture o id** da resposta da API (header `X-Request-Id`) — todo request
   recebe um (gerado pelo `RequestIdFilter` se o cliente não enviar).
2. **No agregador (drain):** filtre por `requestId="<id>"` → ache o `ts`, o
   `logger` e o `msg` dos eventos daquele request (chain de chamadas).
3. **Sem drain (janela da UI do Railway):** aba Logs → filtro no `requestId`.
4. **Na trilha de auditoria** (`platform_activity_log`), cruze pela janela de
   tempo do request:

```sql
-- Eventos do ator na janela do requestId (ajuste o intervalo ao ts do log)
SELECT created_at, actor_type, action, entity_type, entity_id, details
FROM platform_activity_log
WHERE created_at BETWEEN '2026-08-15T00:59:30Z' AND '2026-08-15T01:00:30Z'
  AND (actor_type = 'MERCHANT' OR action LIKE 'MFA_%' OR action LIKE '%LOGIN%')
ORDER BY created_at;
```

5. **Nas métricas:** `/actuator/prometheus` na mesma janela — contadores
   `foxcash_*` (falhas de login, 429, latência `http_server_requests_*` se
   configurado) para dimensionar o incidente.

## 4. Retenção da trilha (opt-in — Spec 95)

O `platform_activity_log` é **append-only** por contrato (Spec 41 — a API não
tem rota de mutação). Quando o volume exigir, habilite o job de expurgo
(`PlatformActivityCleanupScheduler`, padrão Spec 53/ShedLock):

```yaml
foxcash:
  scheduler:
    activity-log-cleanup-enabled: true   # default false (append-only preservado)
    activity-log-cleanup-cron: "0 15 4 * * *"   # UTC, default 04:15
    activity-log:
      retention-days: 180                 # default 180
```

O job roda via `JobExecutionService` (trilha `job_runs` + métricas
`foxcash.job.*`); a falha é capturada e alertada (padrão Spec 53) — nunca
re-propaga.

## 5. Referências

- `deploy/railway.md` — topologia, env vars, passo a passo do deploy.
- `ops/security-incident.md` — resposta a incidente (Spec 64), detectar →
  conter → mitigar → restaurar (usa `X-Request-Id` como chave).
- `management/specs/64-sec-observability-incident-response/` — alertas,
  limiares, runbook.
- `management/specs/41-master-profiles-activity/requirements.md` — trilha
  `platform_activity_log` (append-only).
- `management/specs/53-job-robustez/requirements.md` — `JobExecutionService` +
  ShedLock.
