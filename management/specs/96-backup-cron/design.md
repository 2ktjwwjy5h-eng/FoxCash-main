---
name: Backup automático — instalar/verificar o cron
status: audited
---

# Spec 96 — Design: Backup automático (instalar/verificar o cron)

## Estado atual (não refazer)

| Item | Onde | Status |
|---|---|---|
| Dump diário | `ops/backup.sh` (pg_dump `-Fc`, lock, validação, retenção 14d) | ✅ validado 2026-08-07 |
| Restauração | `ops/restore.sh` (DROP+CREATE+pg_restore, validação) | ✅ validado 2026-08-07 |
| Runbook de DR | `ops/backup-restore.md` | ✅ validado 2026-08-07 |
| **Agendamento cron** | manual (`crontab -e`) — sem automação | ❌ **lacuna** |
| **Verificação (frescura/cron)** | — | ❌ **lacuna** |
| **Rotativo do log de backup** | prometido no runbook ("60 dias") mas sem conf | ❌ **lacuna** |

## Entregáveis

### 1. `ops/install-backup-cron.sh` — instalador/verificador (bash, sem deps)

Três modos por subcomando (`install` default, `check`, `uninstall`):

- **Resolução do caminho**: `SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)`
  → `BACKUP_SCRIPT="$SCRIPT_DIR/backup.sh"` (caminho absoluto no cron, funciona
  de qualquer CWD).
- **Linha do cron**: `30 2 * * * "$BACKUP_SCRIPT" >> /var/log/nyxcash-backup.log 2>&1`
  (diário 02:30 UTC — fora do pico).
- **`install`**: verifica `backup.sh` executável; se `crontab` indisponível →
  mensagem com alternativas (WSL `crontab`, Windows Task Scheduler, ou
  `NYXCASH_BACKUP_SCHEDULE`) e exit 1; senão, **idempotente**: `crontab -l |
  grep -F "$LINE"` presente → "já instalado" (exit 0), ausente → append via
  `(crontab -l 2>/dev/null; echo "$LINE") | crontab -` (preserva entradas
  existentes). Instala o logrotate quando `/etc/logrotate.d/` é gravável.
- **`check`**: valida (a) linha no crontab; (b) `backup.sh` executável; (c)
  **frescura**: último `nyxcash_*.dump` em `BACKUP_DIR` (default
  `/var/backups`) com mtime ≤ 48h; (d) dir de backup legível. Imprime
  `[ok]/[FALHA]` por item e exit 0/1 (saída legível por humano/agente).
- **`uninstall`**: remove a linha (grep -v) — idempotente.
- Variáveis: `BACKUP_DIR`, `CRON_SCHEDULE` (default `30 2 * * *`), `LOG_FILE`
  (default `/var/log/nyxcash-backup.log`), `MAX_DUMP_AGE_HOURS` (default 48).
- Erros sempre com `[backup-cron]` prefixo; `set -euo pipefail` com os
  checks de tool availability feitos explicitamente (não deixar o pipefail
  mascarar o `crontab -l` quando o crontab não existe).

### 2. `ops/logrotate-nyxcash-backup.conf`

```
/var/log/nyxcash-backup.log {
    daily
    rotate 60
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
}
```

### 3. `ops/backup-restore.md` — atualização

- Seção "Rotina de backup": substitui o `crontab -e` manual pelo instalador
  (`./ops/install-backup-cron.sh install` / `check` / `uninstall`).
- **Checklist de verificação** (novo):
  - Diário (automático): cron roda → dump do dia existe.
  - Semanal: `./ops/install-backup-cron.sh check` (frescura + cron).
  - Mensal: **teste de restore** em banco descartável
    (`CREATE DATABASE nyxcash_restore_test` + `restore.sh` apontando para ela,
    validando contagens e a última migration) — mesmo procedimento do DR test
    de 2026-08-07.
  - Pós-falha: restaurar o dump mais recente (runbook existente §6–7).

## Decisões registradas

- **D1** Sem mudança no `backup.sh`/`restore.sh` — já validados; o instalador
  só agenda/verifica (mínima superfície).
- **D2** Idempotência por `grep -F` da linha exata no crontab — se o operador
  já instalou manualmente, `install` reconhece e não duplica.
- **D3** Modo `check` com janela de 48h para o dump — o cron é diário; 2 dias
  de atraso = falha silenciosa provável (deteção sem falso positivo por 1 dia
  perdido).
- **D4** Sem código Java — spec 100% operacional (bash + docs); a validação é
  `bash -n` + execução dos modos em ambiente controlado.
