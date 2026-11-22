# Backup & Restauração do PostgreSQL — runbook de Disaster Recovery

> **VALIDADO EM 2026-08-07** — teste de DR completo executado localmente:
> backup → DROP DATABASE → recriar do zero (Flyway V1..V42) → boot → smoke →
> restore de dados. Tudo recuperado (8 merchants, 5 masters, 38 migrations).
> Este runbook documenta o procedimento **e as lições do teste real**.

## Visão geral

O banco `foxcash` (PostgreSQL) é a única fonte de estado persistente. Em caso de
perda total (drop acidental, corrupção, desastre), a recuperação tem **dois
caminhos independentes**:

1. **Schema**: recriado do zero pelas migrations Flyway (`V1..V42`) — o boot da
   aplicação aplica automaticamente as pendentes. **Nunca perde** (está no git).
2. **Dados**: recuperados do backup `pg_dump` — o backup é a única cópia dos
   dados de negócio (lojistas, transações, ledgers).

**Objetivo de recuperação:** o schema volta a operar em ~30s (migrations em
0.23s + boot ~6s). Os dados voltam quando o backup é restaurado.

## Pré-requisitos

- Credenciais de acesso: `nyxcash/nyxcash@localhost:5432/nyxcash` (dev).
- O usuário `foxcash` é **dono** do banco (pode DROP) e tem `CREATEDB`
  (concedido no teste de DR de 2026-08-07) — pode recriar o banco sem superuser.
- Backup mais recente em local conhecido (ver "Rotina de backup" abaixo).

## Procedimento de recuperação (perda total)

### 1. Parar a aplicação

```bash
# Evita escrita concorrente durante a recuperação.
# (Na prática, se o banco foi perdido, a app já está falhando — mas pare o
# processo para não gerar erros em loop.)
```

### 2. Backup de segurança do estado atual (se ainda existir algo)

```bash
pg_dump -h localhost -U nyxcash -d nyxcash -Fc -f /var/backups/nyxcash-pre-recovery.dump
```

### 3. Recriar o banco do zero

```bash
# O usuário dono pode dropar; recriar exige CREATEDB (já concedido) ou superuser.
psql -h localhost -U foxcash -d postgres -c "DROP DATABASE IF EXISTS foxcash;"
psql -h localhost -U nyxcash -d postgres -c "CREATE DATABASE nyxcash OWNER nyxcash;"
```

### 4. Migrar o schema (Flyway) + validar

**Opção A — via boot da aplicação (recomendado):** sobe a app no banco vazio;
o Flyway aplica `V1..V42` automaticamente e o Hibernate (`ddl-auto: validate`)
confirma que o schema bate com as entities.

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
# Log esperado: "Successfully applied 38 migrations to schema public, now at version v42"
#               "Started FoxCashApplication"
```

**Opção B — só o schema, sem subir a app:**

```bash
mvn -B flyway:migrate -Dflyway.url="jdbc:postgresql://localhost:5432/nyxcash" \
  -Dflyway.user=foxcash -Dflyway.password=foxcash
```

### 5. Smoke test — "voltar a operar"

```bash
curl http://localhost:8080/actuator/health          # → {"status":"UP",...}
curl -H "X-Api-Key: dev-only-admin-api-key" \
  http://localhost:8080/api/v1/admin/merchants      # → 200 (stack completa)
```

Neste ponto o **schema opera** (vazio, apenas o seed: master admin + opening
balances). Dados de negócio ainda não — próximo passo.

### 6. Restaurar os dados do backup

```bash
pg_restore -h localhost -U nyxcash -d nyxcash --clean --if-exists \
  /var/backups/nyxcash_YYYY-MM-DD.dump
```

**Atenção:** `--clean` recria objetos que já existem (o schema migrado pelo
Flyway é substituído pelos objetos do dump). Isso é OK porque o dump contém o
schema **e** os dados do momento do backup. Se quiser preservar o schema
migrado e só carregar dados, usar sem `--clean` (mas objetos duplicados
falharão — ver "Decisões" abaixo).

### 7. Confirmar restauração

```bash
psql -h localhost -U nyxcash -d nyxcash -tAc "SELECT count(*) FROM merchants;"   # esperado: N
psql -h localhost -U nyxcash -d nyxcash -tAc "SELECT count(*) FROM masters;"      # esperado: M
psql -h localhost -U nyxcash -d nyxcash -tAc \
  "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" # → 42
curl -H "X-Api-Key: dev-only-admin-api-key" \
  http://localhost:8080/api/v1/admin/merchants      # → 200
```

## Rotina de backup (recomendada)

Backup **diário** + retenção de 14 dias — automatizado pelos scripts
versionados `ops/backup.sh` e `ops/restore.sh` (validados em 2026-08-07):

```bash
# Backup manual:
./ops/backup.sh                                    # → /var/backups/nyxcash_YYYY-MM-DD.dump
BACKUP_DIR=/custom/path ./ops/backup.sh            # diretório custom
RETENTION_DAYS=30 ./ops/backup.sh                  # retenção custom

# Restauração (destrutiva — pede confirmação):
./ops/restore.sh /var/backups/nyxcash_2026-08-07.dump   # perda total (DROP+CREATE+restore)
./ops/restore.sh                                     # usa o backup mais recente
RESTORE_MODE=data ./ops/restore.sh FILE             # só dados (schema já migrado)
```

**Agendamento no cron (diário 02:30 UTC — fora do pico) — automatizado (Spec 96):**

```bash
# Instala o cron de forma IDEMPOTENTE (linha `30 2 * * * <abs>backup.sh >> /var/log/nyxcash-backup.log 2>&1`)
./ops/install-backup-cron.sh install

# Verifica a saúde da rotina (cron presente + backup.sh executável + dump < 48h)
./ops/install-backup-cron.sh check

# Remove o cron (idempotente)
./ops/install-backup-cron.sh uninstall
```

O instalador também copia o rotativo do log
(`ops/logrotate-nyxcash-backup.conf` → `/etc/logrotate.d/`, 60 dias) quando tem
permissão — fecha a promessa de "rotativo simples". Em Windows/Git Bash (sem
`crontab`), o `install` imprime as alternativas (WSL/Task Scheduler) sem
alterar nada.

**Alternativa: serviço `backup-cron` no Railway (Spec 96)** — para produção no
Railway, em vez do cron do host, use o contêiner do diretório `ops/`:

```bash
cd ops
railway up --detach                    # serviço 'backup-cron' na MESMA rede privada
railway volume add --service backup-cron --mount-path /backups   # durabilidade
```

- `ops/Dockerfile` — `postgres:18-alpine` (pg_dump 18 = versão do servidor
  Railway; pg_dump antigo não despeja servidor mais novo) + `bash`.
- `ops/backup-cron-entrypoint.sh` — interpreta a `DATABASE_URL` injetada pelo
  Postgres do Railway (`postgresql://user:pass@host:port/db`) → `DB_*` do
  `backup.sh`, roda o backup a cada **24h** (loop `while true` + `sleep 86400`).
  Falha de um ciclo **não** mata o loop (próximo tenta de novo); log no stdout
  (Railway Logs / log drain — Spec 95).
- O dump fica em `/backups` — **anexe o Volume** acima, senão o dump morre no
  redeploy do contêiner.
- Verificação: `railway logs --service backup-cron` deve mostrar
  `[backup-cron] executando backup em ...` + o dump `nyxcash_YYYY-MM-DD.dump` em
  `/backups`. O checklist da Spec 96 (§abaixo) aplica-se com o dump via
  `railway run -- ls /backups`.

O script `backup.sh` cuida de: lock anti-sobreposição (flock; fallback mkdir no
Windows), validação de sanidade do dump (tamanho mínimo) **+ validação do
artefato `pg_restore --list` (Spec 135/C5 — dump truncado/corrompido falha na
hora, nunca passa despercebido)**, retenção automática e detecção do binário
pg_dump (PATH, `PG_BIN`, ou `C:\Program Files\PostgreSQL\*\bin`).

### Checklist de verificação da rotina (Spec 96)

| Frequência | Verificação | Comando |
|---|---|---|
| Diário (automático) | O dump do dia existe em `BACKUP_DIR` | `ls -t /var/backups/nyxcash_*.dump` |
| Semanal | Saúde do agendamento + frescura | `./ops/install-backup-cron.sh check` (exit 0 = ok) |
| Mensal | **Teste de restore** em banco descartável | ver abaixo |
| Pós-falha do cron | Dump velho detectado pelo `check` → investigar log | `tail /var/log/nyxcash-backup.log` |

**Teste de restore (mensal — mesmo procedimento do DR test de 2026-08-07):**

```bash
# 1. Banco descartável (o usuário dono tem CREATEDB)
createdb -h localhost -U foxcash foxcash_restore_test

# 2. Restaura o dump mais recente nesse banco (sem DROP do banco principal)
BACKUP_FILE=$(ls -t /var/backups/nyxcash_*.dump | head -1)
pg_restore -h localhost -U nyxcash -d nyxcash_restore_test --clean --if-exists "$BACKUP_FILE"

# 3. Valida: contagens básicas + última migration
psql -h localhost -U nyxcash -d nyxcash_restore_test -c \
  "SELECT count(*) FROM merchants; SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"

# 4. Descarta o banco de teste
dropdb -h localhost -U foxcash foxcash_restore_test
```

> **Formato custom (`-Fc`)** — recomendado: comprimido, suporta `pg_restore`
> seletivo e paralelo. Alternativa: `pg_dump` plano (`.sql`) — portátil entre
> versões mas sem restore seletivo.

## Decisões e riscos (lições do teste de 2026-08-07)

### 1. Nunca substituir `pg_hba.conf` com um editor que muda a ACL (INCIDENTE REAL)

Durante o DR test, ao tentar recuperar acesso de superuser editando o
`pg_hba.conf`, o `cp`/`mv` do shell **recriou o arquivo com ACL restrita** ao
usuário atual — o serviço Postgres (roda como `NT AUTHORITY\NetworkService`)
perdeu a leitura e **TODAS as conexões falharam** (`FATAL: could not load
pg_hba.conf: Permission denied`), mesmo com o arquivo de volta ao conteúdo
original.

**Recuperação:** `icacls pg_hba.conf /grant "*S-1-5-20:R"` (NetworkService) —
restaurou a leitura **sem reiniciar o serviço** (o pg_hba é relido por conexão).

**Regra:** para editar `pg_hba.conf`, **editar in-place** (`sed -i`) ou
**restaurar ACL após qualquer substituição** (`icacls ... /grant "*S-1-5-20:R"`).
Nunca `mv` de outro diretório.

### 2. `DROP DATABASE` exige ser dono; `CREATE DATABASE` exige `CREATEDB`

O usuário `foxcash` (dono) pode dropar, mas **não pode criar** banco sem a role
`CREATEDB`. No teste, concedemos via superuser:
`ALTER ROLE foxcash CREATEDB;` — garanta que a role está concedida em produção,
ou o runbook exige o superuser `postgres` para o passo 3.

### 3. Restore com `--clean` vs schema migrado

O dump custom (`-Fc`) contém schema + dados. Restaurar com `--clean --if-exists`
**substitui** o schema migrado pelo do backup — consistente (mesmo estado).
Se o backup for de uma versão de schema diferente da atual, o `flyway_schema_history`
do dump pode divergir do classpath — nesse caso, restaurar e depois rodar
`flyway:migrate` para alinhar (ou restaurar só dados). **Valide o `version` final
da `flyway_schema_history` após o restore** (passo 7).

### 4. O seed roda no boot e recria o master admin

Banco vazio + boot → `MasterSeedRunner` cria o master `master`/senha dev e o
`OpeningBalanceSeedRunner` cria opening balances dos lojistas. Isso é esperado
no schema recriado (passo 4) e é substituído pelos dados reais no restore
(passo 6) — o master admin do seed pode coexistir com os masters do backup.

## Disaster Recovery — RPO/RTO e failover (Spec 135)

> Estado 2026-08-17: backup **6/6h (RPO ≤ 4h)** — cron `30 2,8,14,20 * * *`
> UTC (4×/dia, Spec 135 T2.1 — decisão do usuário 2026-08-17), dump em
> `/backups` (volume Railway) com nome `foxcash_YYYY-MM-DD_HHMM.dump`. O **DR
> drill real** (C1 — restore em banco vazio com evidência registrada) foi
> **executado 2026-08-17** — ver tabela RPO/RTO abaixo.

### RPO / RTO (estado medido 2026-08-17)

| Métrica | Valor |
|---|---|
| **RPO** (perda máx. de dados) | **≤ 4h** (backup 6/6h — `30 2,8,14,20 * * *` UTC; decisão do usuário 2026-08-17) |
| **RTO** (tempo de retorno) | **~1s por 500 KB de dump** (DR drill 2026-08-17: `pg_restore` de 477 KB em **0.646s** local); em produção o RTO real é dominado pelo provisionamento do banco vazio (Railway) — medir no próximo drill em staging Railway |

**DR drill 2026-08-17 (evidência):** backup 477 KB (artefato validado por
`pg_restore --list`) → banco staging vazio → `pg_restore --no-owner` exit 0 em
0.646s → 54 tabelas / 66 migrations até V77 (todas `success=true`) / dados
idênticos ao primário (61 merchants, 123 outbox, 113 ledger) → `flyway:validate`
no staging: `Successfully validated 66 migrations` (a app sobe com o banco
restaurado).

### Passos de failover (perda total do banco)

1. **Parar a app** (rollout zero-downtime pausa novas requisições; se o banco
   caiu, a app já está falhando — pare as réplicas para evitar erro em loop).
2. **Localizar o backup mais recente e válido** — `pg_restore --list` já
   valida o artefato na geração (`backup.sh`); confirme o dump do dia existe:
   `ls -t /backups/foxcash_*.dump | head -1` (Railway: `railway run -- ls /backups`).
3. **Recriar o banco vazio** — DROP + CREATE (dono `foxcash` com CREATEDB; se a
   role não tiver, superuser `postgres` — lição 2 abaixo).
4. **Restaurar o dump** — `pg_restore --clean --if-exists` (procedimento
   completo no "Procedimento de recuperação" acima, passos 1-7).
5. **Validar** — contagens (`merchants`, `masters`), última migration da
   `flyway_schema_history`, reconciliação wallet×ledger 0/0, health 200.
6. **Retomar o tráfego** — `railway up` / deploy das réplicas; healthcheck
   valida readiness antes do tráfego (zero-downtime, Spec 61).

**Quem faz:** operador com acesso ao Railway + credenciais do Postgres.
**Contatos:** equipe de operações — Railway (`foxcash-prod`) e
Cloudflare (R2/edge); alertas automatizados no Discord do operador (webhook
Spec 64) e no log do `backup-cron` (Railway Logs / log drain).
**Tempos esperados:** RTO medido no DR drill (C1) — ver tabela acima; nunca
estimados (diretriz 2026-08-08).
**Backup fora da região (T2.2):** **ATIVO em produção (2026-08-17)** — o
`backup.sh` replica o dump para o bucket Cloudflare R2 `foxcash-backups` via
`ops/backup-upload.sh` (rclone, multi-região); credenciais `R2_*` no serviço
`backup-cron` (Railway), validado ao vivo (`[backup-upload] OK: ... replicado no
bucket`).

### Backup fora da região — ativação (Spec 135/T2.2)

**Objetivo:** o dump em `/backups` (volume Railway, sfo) é do MESMO provedor e
região do banco primário — se a região ou o provedor cair, o backup cai junto.
Replicar o dump para um bucket externo **multi-região** fecha essa lacuna (a
pergunta de due diligence "se o provedor falhar, há um caminho?").

**Passos (uma vez):**

1. **Criar o bucket no Cloudflare R2** (painel Cloudflare → R2 → **Create
   bucket**, ex.: `foxcash-backups`). R2 é multi-região por default e tem egress
   grátis — o domínio já está no Cloudflare (Spec 63). Qualquer bucket
   S3-compatível serve (AWS S3, B2, MinIO...) — basta trocar as envs.
2. **Criar um token de API** (R2 → **Manage API Tokens** → **Create API Token**)
   com permissão **Object Read & Write** (só no bucket do backup).
3. **Definir as envs no serviço `backup-cron` do Railway** (nunca no repo —
   painel Railway → serviço → Variables):
   ```
   R2_ENDPOINT=https://<accountid>.r2.cloudflarestorage.com
   R2_BUCKET=foxcash-backups
   R2_ACCESS_KEY_ID=<access key>
   R2_SECRET_ACCESS_KEY=<secret>
   R2_PREFIX=foxcash
   R2_RETENTION_DAYS=14
   ```
4. **Deploy do `backup-cron`** com o Dockerfile novo (instala o rclone e o
   `backup-upload.sh`). Se usar o caminho host (VPS/cron), reinstall:
   `./ops/install-backup-cron.sh install` e instale o rclone no host
   (`apk add rclone` / `apt install rclone` / binário oficial).
5. **Verificar** — rodar `./ops/backup.sh` manual e conferir no log:
   `[backup] Replicando para bucket fora da região...` →
   `[backup-upload] OK: <dump> replicado no bucket (… bytes)`; conferir o objeto
   no painel R2. No Railway: `railway logs --service backup-cron`.

**Comportamento (código):** com `R2_BUCKET` definido, **falha na replicação
FALHA o backup** (exit ≠ 0 — o cron/entrypoint alarma): um dump que não foi
copiado para fora da região não cumpre a promessa de DR/RPO. Sem `R2_BUCKET`, o
backup é só local (comportamento anterior, sem quebra).
