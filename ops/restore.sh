#!/usr/bin/env bash
#
# restore.sh — restaura o banco FoxCash a partir de um backup pg_dump.
#
# Uso:
#   ./ops/restore.sh /var/backups/nyxcash_2026-08-07.dump
#   ./ops/restore.sh                          # usa o backup mais recente
#   RESTORE_MODE=data ./ops/restore.sh FILE   # restaura só dados (sem --clean)
#
# Comportamento (perda total):
#   1. Confirma o backup existe e não está vazio
#   2. DROP + CREATE DATABASE (schema vazio — o backup traz schema + dados)
#   3. pg_restore --clean --if-exists (substitui o que o Flyway migrou)
#   4. Valida: contagens básicas + última migration da flyway_schema_history
#
# ATENÇÃO: destrutivo — sobrescreve o banco atual. Faça backup antes se
# precisar preservar o estado corrente (ver backup.sh).
#
# Config via env: DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD/DB_NAME
# (padrão localhost/5432/nyxcash/nyxcash/nyxcash).

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuração
# ---------------------------------------------------------------------------

PGHOST="${DB_HOST:-localhost}"
PGPORT="${DB_PORT:-5432}"
PGUSER="${DB_USERNAME:-nyxcash}"
export PGPASSWORD="${DB_PASSWORD:-nyxcash}"
PGDATABASE="${DB_NAME:-nyxcash}"

BACKUP_FILE="${1:-}"
MODE="${RESTORE_MODE:-full}"   # full | data

# Localiza os binários do PostgreSQL: PATH (Linux) ou diretório padrão do
# Windows (C:\Program Files\PostgreSQL\<ver>\bin). Override via PG_BIN.
if [ -n "${PG_BIN:-}" ]; then
    export PATH="$PG_BIN:$PATH"
elif ! command -v psql >/dev/null 2>&1; then
    PG_WIN_BIN=$(ls -d "/c/Program Files/PostgreSQL/"*/bin 2>/dev/null | sort -V | tail -1)
    if [ -n "$PG_WIN_BIN" ]; then
        export PATH="$PG_WIN_BIN:$PATH"
    fi
fi

if ! command -v psql >/dev/null 2>&1 || ! command -v pg_restore >/dev/null 2>&1; then
    echo "[restore] psql/pg_restore não encontrados (PATH, PG_BIN ou C:\\Program Files\\PostgreSQL\\*\\bin)." >&2
    exit 1
fi

if [ -z "$BACKUP_FILE" ]; then
    BACKUP_FILE=$(ls -t /var/backups/nyxcash_*.dump 2>/dev/null | head -1)
    if [ -z "$BACKUP_FILE" ]; then
        echo "[restore] Nenhum backup informado e nenhum encontrado em /var/backups." >&2
        exit 1
    fi
    echo "[restore] Usando backup mais recente: ${BACKUP_FILE}"
fi

if [ ! -f "$BACKUP_FILE" ]; then
    echo "[restore] Backup não encontrado: ${BACKUP_FILE}" >&2
    exit 1
fi
if [ ! -s "$BACKUP_FILE" ]; then
    echo "[restore] Backup vazio: ${BACKUP_FILE}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Confirmação
# ---------------------------------------------------------------------------

echo "[restore] ATENÇÃO: isto sobrescreve o banco '${PGDATABASE}' (${PGHOST}:${PGPORT})."
read -r -p "Confirmar? (yes/no): " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "[restore] Abortado pelo usuário."
    exit 1
fi

# ---------------------------------------------------------------------------
# Restauração
# ---------------------------------------------------------------------------

if [ "$MODE" = "full" ]; then
    # Perda total: recria o banco vazio (dono = usuário; exige CREATEDB ou superuser).
    echo "[restore] DROP + CREATE DATABASE ${PGDATABASE}"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
        -c "DROP DATABASE IF EXISTS ${PGDATABASE};" \
        -c "CREATE DATABASE ${PGDATABASE} OWNER ${PGUSER};"

    echo "[restore] pg_restore --clean --if-exists ${BACKUP_FILE}"
    pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        --clean --if-exists "$BACKUP_FILE"
else
    # Só dados: restaura sem dropar objetos (exige schema já migrado compatível).
    echo "[restore] pg_restore (dados apenas) ${BACKUP_FILE}"
    pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
        --data-only --disable-triggers "$BACKUP_FILE"
fi

# ---------------------------------------------------------------------------
# Validação
# ---------------------------------------------------------------------------

echo "[restore] Validando restauração..."
MERCHANTS=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "SELECT count(*) FROM merchants;" 2>/dev/null || echo "N/A")
MASTERS=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "SELECT count(*) FROM masters;" 2>/dev/null || echo "N/A")
VERSION=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>/dev/null || echo "N/A")

echo "[restore] merchants=${MERCHANTS} masters=${MASTERS} última migration=${VERSION}"
echo "[restore] Restauração concluída. Suba a aplicação e confirme o health:"
echo "         curl http://localhost:8080/actuator/health"
