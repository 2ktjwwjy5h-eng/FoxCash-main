---
name: Backup automático — instalar/verificar o cron
status: audited
---

# Spec 96 — Task: Backup automático (instalar/verificar o cron)

## Scripts

- [x] `ops/install-backup-cron.sh` (novo, bash, sem deps) — modos:
  - [x] `install` (default): resolve o caminho absoluto do `backup.sh`,
        verifica executável, monta a linha `30 2 * * * <abs>backup.sh >>
        /var/log/nyxcash-backup.log 2>&1`, **idempotente** (`crontab -l |
        grep -F` → "já instalado"; senão append preservando entradas);
        `crontab` indisponível → mensagem com alternativas (WSL/Task
        Scheduler) + exit 1.
  - [x] `check`: valida (a) linha no crontab; (b) `backup.sh` executável;
        (c) frescura do último `nyxcash_*.dump` (< `MAX_DUMP_AGE_HOURS`,
        default 48); (d) dir de backup legível. Saída `[ok]/[FALHA]` por item
        + exit 0/1.
  - [x] `uninstall`: remove a linha (idempotente).
  - [x] Variáveis `BACKUP_DIR`/`CRON_SCHEDULE`/`LOG_FILE`/`MAX_DUMP_AGE_HOURS`
        com defaults; prefixo `[backup-cron]` nos logs.
- [x] `ops/logrotate-nyxcash-backup.conf` (novo) — diário, `rotate 60`,
        `missingok`, `notifempty`, `compress`, `delaycompress`, `copytruncate`
        para `/var/log/nyxcash-backup.log`.

## Docs

- [x] `ops/backup-restore.md` — substituir o `crontab -e` manual pelo
      instalador (`install`/`check`/`uninstall`) + **checklist de
      verificação** (semanal: `check`; mensal: teste de restore em banco
      descartável; pós-falha: restaurar o mais recente).

## Aditivo pós-auditoria (commit `e6863d6`) — serviço `backup-cron` no Railway

- [x] `ops/Dockerfile` — `postgres:18-alpine` (pg_dump 18 = versão do servidor
      Railway; pg_dump antigo não despeja servidor mais novo) + `bash`; copia
      `backup.sh`/`backup-cron-entrypoint.sh`; `BACKUP_DIR=/backups`.
- [x] `ops/backup-cron-entrypoint.sh` — interpreta a `DATABASE_URL` injetada
      pelo Postgres do Railway → `DB_*` do `backup.sh`; loop `while true` +
      `sleep 86400` (backup a cada 24h na rede privada); falha de um ciclo **não**
      mata o loop; log no stdout (Railway Logs / log drain Spec 95).
- [x] `ops/backup-restore.md`/`deploy/railway.md` — deploy do serviço
      (`railway up` + volume `/backups`) documentado como alternativa ao cron
      do host.

## Validação

- [x] `bash -n` limpo em `install-backup-cron.sh`.
- [x] `install` idempotente testado (crontab real ou descartável).
- [x] `check` com/ sem dump fresco → exit 0/1 correto.
- [x] `uninstall` idempotente.

## Docs + roadmap

- [x] `management/vision.md` — bloco **V72** (via `/spec-plan`).
- [x] `management/roadmap.md` — Spec 96 `proposed` → `planned` → `audited` +
      narrativa; ADR-018 (decisão #10) atualizado.
- [x] Commit final com mensagem descritiva.
