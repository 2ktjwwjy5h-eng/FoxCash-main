#!/usr/bin/env bash
#
# backup.sh — backup do PostgreSQL FoxCash (pg_dump custom).
#
# Uso:
#   ./ops/backup.sh                      # backup no diretório padrão
#   BACKUP_DIR=/custom/path ./ops/backup.sh
#
# Comportamento:
#   - pg_dump -Fc (custom: comprimido, suporta pg_restore seletivo/paralelo)
#   - Nome com data+hora: foxcash_YYYY-MM-DD_HHMM.dump (RPO 6/6h — Spec 135:
#     até 4 backups/dia sem colisão de nome)
#   - Retenção: apaga backups com mtime > RETENTION_DAYS (default 14)
#   - Lock (flock) evita execuções sobrepostas (cron disparando de novo)
#   - Valida o dump criado (arquivo não vazio), o tamanho mínimo E o artefato
#     (`pg_restore --list` — Spec 135/C5: dump corrompido/truncado falha na hora)
#   - Log no stdout + exit code (cron detecta falha)
#
# Config via env (mesmas credenciais da aplicação):
#   PGHOST/PGPORT/PGUSER/PGPASSWORD ou DB_* — default localhost/5432/nyxcash
#   BACKUP_DIR (default /var/backups)
#   RETENTION_DAYS (default 14)
#
# Instalação no cron (Spec 135 — RPO 6/6h: 02:30/08:30/14:30/20:30 UTC):
#   crontab -e
#   30 2,8,14,20 * * * /caminho/do/projeto/ops/backup.sh >> /var/log/nyxcash-backup.log 2>&1
#   (ou: ./ops/install-backup-cron.sh install — aplica o agendamento)

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuração
# ---------------------------------------------------------------------------

BACKUP_DIR="${BACKUP_DIR:-/var/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

# Diretório dos scripts (para localizar o backup-upload.sh ao lado).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Credenciais: DB_* (padrão do application.yml) OU PGPASSWORD/PGHOST etc.
PGHOST="${DB_HOST:-${PGHOST:-localhost}}"
PGPORT="${DB_PORT:-${PGPORT:-5432}}"
PGUSER="${DB_USERNAME:-${PGUSER:-nyxcash}}"
export PGPASSWORD="${DB_PASSWORD:-${PGPASSWORD:-nyxcash}}"
PGDATABASE="${DB_NAME:-nyxcash}"

# ---------------------------------------------------------------------------
# Preparação
# ---------------------------------------------------------------------------

mkdir -p "$BACKUP_DIR"

# Lock: evita backup duplicado se o cron disparar enquanto um já roda.
# Prefere flock (Linux); fallback portátil via mkdir (Windows/Git Bash).
LOCK_DIR="${BACKUP_DIR}/.backup.lockdir"
LOCK_FILE="${BACKUP_DIR}/.backup.lock"

if command -v flock >/dev/null 2>&1; then
    exec 9>"$LOCK_FILE"
    if ! flock -n 9; then
        echo "[backup] Outro backup em andamento (lock: ${LOCK_FILE}). Abortando." >&2
        exit 1
    fi
else
    # mkdir é atômico e portátil — evita execuções sobrepostas sem flock.
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        echo "[backup] Outro backup em andamento (lock: ${LOCK_DIR}). Abortando." >&2
        exit 1
    fi
    trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT
fi

# Localiza os binários do PostgreSQL: no PATH (Linux) ou no diretório padrão
# do Windows (C:\Program Files\PostgreSQL\<ver>\bin). Permite override via
# PG_BIN (ex.: PG_BIN=/usr/lib/postgresql/16/bin).
if [ -n "${PG_BIN:-}" ]; then
    export PATH="$PG_BIN:$PATH"
elif ! command -v pg_dump >/dev/null 2>&1; then
    PG_WIN_BIN=$(ls -d "/c/Program Files/PostgreSQL/"*/bin 2>/dev/null | sort -V | tail -1)
    if [ -n "$PG_WIN_BIN" ]; then
        export PATH="$PG_WIN_BIN:$PATH"
    fi
fi

# Checagem mínima: conexão e binário pg_dump.
if ! command -v pg_dump >/dev/null 2>&1; then
    echo "[backup] pg_dump não encontrado (PATH, PG_BIN ou C:\\Program Files\\PostgreSQL\\*\\bin)." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Backup
# ---------------------------------------------------------------------------

STAMP=$(date +%F_%H%M)
OUT_FILE="${BACKUP_DIR}/foxcash_${STAMP}.dump"

echo "[backup] Iniciando pg_dump -> ${OUT_FILE} (db=${PGDATABASE}, host=${PGHOST}:${PGPORT})"

if ! pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        -Fc -f "$OUT_FILE"; then
    echo "[backup] FALHA no pg_dump." >&2
    exit 1
fi

# Validação de sanidade: arquivo não vazio e acima de um tamanho mínimo
# (um dump de schema+seed nunca é zero — um dump vazio indica falha silenciosa).
MIN_BYTES=1024
SIZE=$(stat -c %s "$OUT_FILE" 2>/dev/null || echo 0)
if [ "$SIZE" -lt "$MIN_BYTES" ]; then
    echo "[backup] FALHA: dump suspeito (${SIZE} bytes < ${MIN_BYTES}). Arquivo ${OUT_FILE} NÃO é confiável." >&2
    rm -f "$OUT_FILE"
    exit 1
fi

# Validação do ARTEFATO (Spec 135/C5): `pg_restore --list` lê o catálogo do
# dump custom (-Fc) e falha se o arquivo estiver truncado/corrompido — um dump
# incompleto (ex.: interrompido no meio da escrita) passa no teste de tamanho,
# mas NÃO é restaurável. Backup corrompido falha na hora, nunca passa despercebido.
if ! pg_restore --list "$OUT_FILE" >/dev/null 2>&1; then
    echo "[backup] FALHA: artefato inválido (pg_restore --list rejeitou ${OUT_FILE})." >&2
    rm -f "$OUT_FILE"
    exit 1
fi

echo "[backup] OK: ${OUT_FILE} (${SIZE} bytes, artefato validado)"

# ---------------------------------------------------------------------------
# Replicação para bucket fora da região (Spec 135/T2.2 — DR)
# ---------------------------------------------------------------------------
# Opcional, ativado por env (R2_BUCKET + credenciais). Se configurado e o
# script de upload existir, falha na replicação FALHA o backup (exit != 0) —
# um dump que não foi copiado para fora da região/provedor do banco não cumpre
# a promessa de DR/RPO (o cron/entrypoint alarma). Sem R2_BUCKET, o
# comportamento é idêntico ao anterior (apenas local).
if [ -n "${R2_BUCKET:-}" ]; then
    UPLOAD_SCRIPT="${BACKUP_UPLOAD_SCRIPT:-${SCRIPT_DIR}/backup-upload.sh}"
    if [ -x "$UPLOAD_SCRIPT" ]; then
        echo "[backup] Replicando para bucket fora da região (R2_BUCKET=${R2_BUCKET})..."
        if ! "$UPLOAD_SCRIPT" "$OUT_FILE"; then
            echo "[backup] FALHA na replicação externa do dump — backup marcado como falho (exige atenção)." >&2
            exit 1
        fi
        echo "[backup] Replicação externa OK."
    else
        echo "[backup] FALHA: R2_BUCKET definido mas script de upload não encontrado/executável: ${UPLOAD_SCRIPT}" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------------------
# Retenção (best-effort — falha de cleanup não falha o backup)
# ---------------------------------------------------------------------------

find "$BACKUP_DIR" -name 'foxcash_*.dump' -mtime "+${RETENTION_DAYS}" -delete 2>/dev/null \
    && echo "[backup] Retenção: removidos backups com mais de ${RETENTION_DAYS} dias" \
    || echo "[backup] Aviso: falha ao limpar backups antigos (ignorado)"

echo "[backup] Concluído com sucesso."
