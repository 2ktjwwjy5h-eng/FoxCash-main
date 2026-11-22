#!/usr/bin/env bash
# dr-probe — Spec 135/T3.1: mede a conectividade/latência da região deste
# contêiner até o Postgres primário (sfo). Saída no stdout (railway logs).
set -uo pipefail

PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-railway}"
PGHOST="${PGHOST:-postgres.railway.internal}"
PGPORT="${PGPORT:-5432}"

echo "[probe] === medição cross-região para ${PGHOST}:${PGPORT} (db=${PGDATABASE}, user=${PGUSER}) — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "[probe] REGION=$(printenv RAILWAY_REGION 2>/dev/null || echo 'RAILWAY_REGION não definida')"
printenv | grep -iE '^RAILWAY_' | grep -viE 'PASSWORD|TOKEN|SECRET|KEY' | sort | head -12

# 1) O hostname privado da rede do Railway resolve a partir DESTA região?
echo "[probe] 1) resolução do hostname privado:"
if getent hosts "$PGHOST" >/dev/null 2>&1; then
    echo "[probe]    ${PGHOST} RESOLVE ✓"
else
    echo "[probe]    ${PGHOST} NÃO RESOLVE ✗ (rede privada Railway é por-região)"
fi

# 2) pg_isready (TCP + ping do protocolo)
echo "[probe] 2) pg_isready (privado):"
if PGPASSWORD="${PGPASSWORD:-}" timeout 20 pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -t 15 >/dev/null 2>&1; then
    echo "[probe]    conecta ✓"
else
    echo "[probe]    FALHA ✗"
fi

# 3) SELECT 1 com medição de tempo (bash time builtin — TIMEFORMAT=%R → segundos)
echo "[probe] 3) SELECT 1 via privado — 5 amostras (segundos):"
TIMEFORMAT='%R'
for i in 1 2 3 4 5; do
    t=$({ time PGPASSWORD="${PGPASSWORD:-}" timeout 20 psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT 1" >/dev/null 2>&1; } 2>&1)
    if [ -n "$t" ] && [ "$t" != "0.000" ]; then
        echo "[probe]    amostra $i: ${t}s"
    else
        echo "[probe]    amostra $i: FALHA (ou <1ms)"
    fi
done

# 4) SELECT 1 WARM — uma única conexão (≈ pool Hikari da app), \timing em ms
echo "[probe] 4) SELECT 1 warm (1 conexão, \timing) — ms:"
PGPASSWORD="${PGPASSWORD:-}" timeout 30 psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
    -c '\timing on' \
    -c 'SELECT 1' -c 'SELECT 1' -c 'SELECT 1' -c 'SELECT 1' -c 'SELECT 1' 2>&1 \
    | grep -E '^Time:' | sed 's/^/[probe]    warm /'

echo "[probe] medição concluída em $(date -u +%H:%M:%SZ). Mantendo vivo 30 min para leitura dos logs."
sleep 1800
