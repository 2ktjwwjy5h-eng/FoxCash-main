#!/usr/bin/env bash
#
# backup-cron-entrypoint.sh — loop 6/6h do serviço backup-cron (Spec 96 + RPO
# 6/6h da Spec 135).
#
# Converte a DATABASE_URL (injetada automaticamente pelo Postgres do Railway)
# para as variáveis DB_* que o backup.sh entende (defaults do application.yml),
# e roda o backup a cada 6h dentro da rede privada. Falha de um backup NÃO
# mata o loop (set -e com || — o próximo ciclo tenta de novo); o log de cada
# execução vai para o stdout (visível no Railway Logs / log drain).

set -euo pipefail

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

export BACKUP_DIR="${BACKUP_DIR:-/backups}"
export RETENTION_DAYS="${RETENTION_DAYS:-14}"

# Replicação fora da região (Spec 135/T2.2): as envs R2_* definidas no serviço
# (R2_BUCKET, R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_PREFIX,
# R2_RETENTION_DAYS) são herdadas pelo contêiner e usadas pelo
# backup.sh/backup-upload.sh automaticamente. Sem R2_BUCKET, o backup é só
# local (comportamento anterior).

# ---------------------------------------------------------------------------
# DATABASE_URL → DB_* (backup.sh prioriza DB_*)
# Formato Railway: postgresql://user:pass@host:port/dbname
# ---------------------------------------------------------------------------
if [ -n "${DATABASE_URL:-}" ]; then
    url="${DATABASE_URL#*://}"       # user:pass@host:port/db
    creds="${url%%@*}"               # user:pass
    hostport="${url#*@}"             # host:port/db
    export DB_USERNAME="${creds%%:*}"
    export DB_PASSWORD="${creds#*:}"
    export DB_HOST="${hostport%%:*}"
    rest="${hostport#*:}"            # port/db
    export DB_PORT="${rest%%/*}"
    export DB_NAME="${rest#*/}"
    export DB_NAME="${DB_NAME%%\?*}" # sem query string
    echo "[backup-cron] DATABASE_URL interpretada: host=${DB_HOST} port=${DB_PORT} db=${DB_NAME} user=${DB_USERNAME}"
else
    echo "[backup-cron] ERRO: DATABASE_URL não definida (Postgres do Railway não injetado?). Usando defaults DB_* do backup.sh." >&2
fi

mkdir -p "$BACKUP_DIR"

# ---------------------------------------------------------------------------
# Loop diário
# ---------------------------------------------------------------------------

echo "[backup-cron] iniciando rotina 6/6h (backup_dir=${BACKUP_DIR}, retention_days=${RETENTION_DAYS}, r2_bucket=${R2_BUCKET:-off})"
while true; do
    echo "[backup-cron] executando backup em $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    /opt/backup.sh || echo "[backup-cron] ERRO no backup deste ciclo — tentando novamente em 6h" >&2
    echo "[backup-cron] próximo backup em 6h (próximo: $(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo 'em 6h'))"
    sleep 21600
done
