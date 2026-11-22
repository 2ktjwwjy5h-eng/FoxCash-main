#!/usr/bin/env bash
#
# cron-body-inspection.sh — wrapper do cron do monitor de body-inspection (R5).
#
# Uso (Agendador de Tarefas do Windows / cron):
#   bash ops/cron-body-inspection.sh
#
# O que faz:
#   1. Define RAILWAY_PROJECT/SERVICE/ENV explicitamente (ID do projeto de
#      produção) — o monitor roda de QUALQUER diretório, sem `railway link`.
#   2. Chama ops/monitor-body-inspection.sh com janela de 15 min e saída JSON.
#   3. Escreve a saída (JSON + erro) no log com timestamp.
#   4. Exit 2 = houve bloqueio/ataque → permite alerta no Agendador (hook).
#
# Config via env (defaults abaixo):
#   RAILWAY_PROJECT (obrigatório — ID do projeto de produção)
#   RAILWAY_SERVICE (default FoxCash)
#   RAILWAY_ENV      (default production)
#   LOG_FILE         (default <repo>/logs/body-inspection-monitor.log)
#
# Agendamento no Windows (a cada 15 min, salva log, alerta opcional no exit 2):
#   schtasks /create /tn "FoxCash Body Inspection Monitor" /sc minute /mo 15 \
#     /tr "\"C:\Program Files\Git\bin\bash.exe\" -lc \"bash /c/caminho/do/projeto/ops/cron-body-inspection.sh\""
#
#   OBS: o Agendador roda com C:\Windows\System32 como cwd — o wrapper usa
#   caminhos absolutos e resolve o próprio diretório, então isso não importa.

set -uo pipefail

# --- Resolve o diretório do repositório (independente do cwd) --------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# --- Config ----------------------------------------------------------------
RAILWAY_PROJECT="${RAILWAY_PROJECT:-00000000-0000-0000-0000-000000000000}"
RAILWAY_SERVICE="${RAILWAY_SERVICE:-FoxCash}"
RAILWAY_ENV="${RAILWAY_ENV:-production}"
MINUTES="${BODY_INSPECTION_MINUTES:-15}"
LOG_FILE="${LOG_FILE:-${REPO_DIR}/logs/body-inspection-monitor.log}"

export RAILWAY_PROJECT RAILWAY_SERVICE RAILWAY_ENV

# --- Garante o diretório do log -------------------------------------------
mkdir -p "$(dirname "$LOG_FILE")"

# --- Executa o monitor ------------------------------------------------------
OUTPUT="$("${SCRIPT_DIR}/monitor-body-inspection.sh" --json --minutes "$MINUTES" 2>&1)"
RC=$?

TIMESTAMP="$(date '+%Y-%m-%dT%H:%M:%S%z')"
{
    echo "[${TIMESTAMP}] exit=${RC} $(printf '%s' "$OUTPUT" | tr '\n' ' ')"
} >> "$LOG_FILE"

# Loga o JSON também (linha separada, legível)
if [ "$RC" -eq 0 ]; then
    printf '%s\n' "$OUTPUT" >> "$LOG_FILE"
fi

# --- Alerta opcional (exit 2 = houve bloqueio) -----------------------------
# Sem dependência de jq (não instalado na máquina do operador): usa python
# para escapar o JSON (mesma detecção do monitor-body-inspection.sh); fallback
# mínimo com sed caso python não exista.
if [ "$RC" -eq 2 ] && [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    MSG="FoxCash: body-inspection detectou bloqueio na janela de ${MINUTES}min — $(printf '%s' "$OUTPUT" | head -c 500)"
    PAYLOAD=""
    if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
        PAYLOAD="$(python3 -c 'import json,sys; print(json.dumps({"content": sys.argv[1]}))' "$MSG")"
    elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
        PAYLOAD="$(python -c 'import json,sys; print(json.dumps({"content": sys.argv[1]}))' "$MSG")"
    else
        SAFE_MSG="$(printf '%s' "$MSG" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ')"
        PAYLOAD="{\"content\": \"$SAFE_MSG\"}"
    fi
    curl -fsS -m 10 -H 'Content-Type: application/json' \
        -d "$PAYLOAD" \
        "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 \
        || echo "[${TIMESTAMP}] ALERTA falhou (curl)" >> "$LOG_FILE"
fi

exit "$RC"
