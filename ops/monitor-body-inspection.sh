#!/usr/bin/env bash
#
# monitor-body-inspection.sh — monitora a inspeção de corpo (Spec 63 camada app).
#
# Uso:
#   ./ops/monitor-body-inspection.sh                  # janela default (últimos 60min)
#   ./ops/monitor-body-inspection.sh --minutes 10     # janela de 10 minutos
#   ./ops/monitor-body-inspection.sh --json           # saída JSON (p/ automação/cron)
#
# O que detecta (nos logs do Railway via CLI, serviço linkado):
#   1. Bloqueios por inspeção de corpo (WARN throttled 1/10s por instância):
#        "Inspecao de corpo bloqueou request: path=.. pattern=.. category=.."
#      → contagem + top path/pattern/category (observação: com throttle, o total
#        é um LIMITE INFERIOR durante rajadas — cada linha representa ≥1 bloqueio).
#   2. Alertas publicados no outbox (limiar 5/60s — definitivo, p/ operador):
#        "ALERTA DE SEGURANCA [BODY_INSPECTION_BLOCKED] publicado: {path=.., ..}"
#
# Exit codes (cron/alerta):
#   0 = sem bloqueios na janela (verde)
#   2 = 1+ bloqueios detectados (cron pode alertar)
#   1 = erro de execução (CLI/logs indisponíveis)
#
# Config via env:
#   BODY_INSPECTION_MINUTES (default 60) — janela em minutos
#   BODY_INSPECTION_LINES   (default 5000 — max da CLI) — teto de linhas lidas (--lines)
#   RAILWAY_SERVICE/RAILWAY_ENV/RAILWAY_PROJECT — override do serviço/ambiente/projeto
#     linkado (RAILWAY_PROJECT = ID do projeto, ex.: <PROJECT_ID>; permite rodar o
#     monitor de QUALQUER diretório sem `railway link`, padrão do cron do operador)
#
# Instalação no cron (a cada 15min; exit 2 = houve bloqueio):
#   crontab -e
#   */15 * * * * /caminho/do/projeto/ops/monitor-body-inspection.sh \
#       >> /var/log/nyxcash-body-inspection.log 2>&1
#   # e, se quiser alertar no exit 2:
#   */15 * * * * /caminho/do/projeto/ops/monitor-body-inspection.sh >/dev/null 2>&1 \
#       || [ $? -eq 2 ] && curl -fsS https://hooks.example.com/... >/dev/null

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuração
# ---------------------------------------------------------------------------

MINUTES="${BODY_INSPECTION_MINUTES:-60}"
LINES="${BODY_INSPECTION_LINES:-5000}"
JSON_MODE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --minutes) MINUTES="${2:-}"; shift 2 ;;
        --minutes=*) MINUTES="${1#*=}"; shift ;;
        --json) JSON_MODE=1; shift ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        *) shift ;;
    esac
done
[ -n "${MINUTES:-}" ] || MINUTES="${BODY_INSPECTION_MINUTES:-60}"

# ---------------------------------------------------------------------------
# TMPDIR: fallback para o Cron/Task Scheduler do Windows (Cygwin/MSYS2).
# O TMPDIR herdado pode apontar para um caminho que o bash Cygwin não resolve
# (ex.: /c/Users/... em vez de /cygdrive/c/Users/...). Se o mktemp falhar no
# TMPDIR atual, tenta os locais canônicos antes de desistir.
# ---------------------------------------------------------------------------
resolve_tmpdir() {
    local d
    # Normaliza caminhos Windows (C:\...) para o formato do shell (MSYS2 /c/...,
    # Cygwin /cygdrive/c/...) quando cygpath existir.
    if command -v cygpath >/dev/null 2>&1; then
        for d in "${TMPDIR:-}" "${TMP:-}" "${TEMP:-}" "${LOCALAPPDATA:-}"; do
            [ -n "$d" ] || continue
            d="$(cygpath -u "$d" 2>/dev/null || printf '%s' "$d")"
            [ -d "$d" ] && [ -w "$d" ] || continue
            TMPDIR="$d"
            return 0
        done
    else
        for d in "${TMPDIR:-}" "$LOCALAPPDATA" /tmp; do
            [ -n "$d" ] && [ -d "$d" ] && [ -w "$d" ] || continue
            TMPDIR="$d"
            return 0
        done
    fi
    # Fallback final: diretório .tmp dentro do repositório (sempre gravável p/ o
    # operador; evita depender do /tmp do Task Scheduler do Windows).
    TMPDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.tmp"
    mkdir -p "$TMPDIR" 2>/dev/null && [ -w "$TMPDIR" ] && return 0
    return 1
}
if ! resolve_tmpdir; then
    echo "ERRO: nenhum diretório temporário gravável encontrado" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Coleta dos logs
# ---------------------------------------------------------------------------

RAILWAY_ARGS=(logs --since "${MINUTES}m" --lines "$LINES")
[ -n "${RAILWAY_PROJECT:-}" ] && RAILWAY_ARGS+=(--project "$RAILWAY_PROJECT")
[ -n "${RAILWAY_SERVICE:-}" ] && RAILWAY_ARGS+=(--service "$RAILWAY_SERVICE")
[ -n "${RAILWAY_ENV:-}" ] && RAILWAY_ARGS+=(--environment "$RAILWAY_ENV")

TMP="${TMPDIR}/body-inspection.$$.log"
: > "$TMP" || { echo "ERRO: não foi possível criar arquivo temporário em $TMPDIR" >&2; exit 1; }
trap 'rm -f "$TMP"' EXIT

if ! railway "${RAILWAY_ARGS[@]}" >"$TMP" 2>/dev/null; then
    echo "ERRO: railway logs falhou (CLI autenticada? serviço linkado?)" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Contagem
# ---------------------------------------------------------------------------

# Bloqueios por inspeção (WARN throttled por bloqueio) — path|pattern|category
BLOCKED_FILE="${TMP}.blocked"; rm -f "$BLOCKED_FILE"
grep -E "Inspecao de corpo bloqueou" "$TMP" \
    | sed -E 's/.*path=([^ ]+) pattern=([^ ]+) category=([^ ]+).*/\1|\2|\3/' \
    > "$BLOCKED_FILE" || true

# Alertas publicados no outbox (limiar 5/60s)
ALERTS="$(grep -c "BODY_INSPECTION_BLOCKED" "$TMP" 2>/dev/null || true)"
ALERTS="${ALERTS:-0}"

BLOCKS="$(wc -l < "$BLOCKED_FILE" 2>/dev/null || echo 0)"
BLOCKS="${BLOCKS:-0}"

# ---------------------------------------------------------------------------
# Saída
# ---------------------------------------------------------------------------

if [ "$JSON_MODE" -eq 1 ]; then
    # Detecta um Python que FUNCIONE (o alias do Windows "python3" é um stub
    # quebrado — exit 49; em Linux o python3 é real; "python" cobre o resto).
    PY=""
    if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
        PY=python3
    elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
        PY=python
    fi
    if [ -n "$PY" ]; then
        "$PY" - "$BLOCKED_FILE" "$BLOCKS" "$ALERTS" "$MINUTES" <<'PY'
import collections, json, sys
path = sys.argv[1]
by_path, by_pattern, by_category = collections.Counter(), collections.Counter(), collections.Counter()
try:
    with open(path, encoding="utf-8") as f:
        for line in f:
            p, pat, cat = line.rstrip("\n").split("|")
            by_path[p] += 1; by_pattern[pat] += 1; by_category[cat] += 1
except FileNotFoundError:
    pass
print(json.dumps({
    "window_minutes": int(sys.argv[4]),
    "blocks": int(sys.argv[2]),
    "alerts": int(sys.argv[3]),
    "by_path": dict(by_path), "by_pattern": dict(by_pattern), "by_category": dict(by_category),
}, ensure_ascii=False, sort_keys=True))
PY
    else
        echo "{\"window_minutes\":$MINUTES,\"blocks\":$BLOCKS,\"alerts\":$ALERTS,\"by_path\":{},\"by_pattern\":{},\"by_category\":{}}"
        echo "AVISO: python indisponível — JSON sem breakdown (use a saída humana)" >&2
    fi
else
    echo "=== Inspeção de corpo — janela: últimos ${MINUTES}min ==="
    echo "Bloqueios detectados (WARN por bloqueio, throttled 1/10s): $BLOCKS"
    echo "Alertas publicados (BODY_INSPECTION_BLOCKED, limiar 5/60s): $ALERTS"
    if [ "$BLOCKS" -gt 0 ]; then
        echo
        echo "--- Top paths ---"
        awk -F'|' '{print $1}' "$BLOCKED_FILE" | sort | uniq -c | sort -rn | head -10
        echo "--- Top patterns ---"
        awk -F'|' '{print $2}' "$BLOCKED_FILE" | sort | uniq -c | sort -rn | head -10
        echo "--- Top categories ---"
        awk -F'|' '{print $3}' "$BLOCKED_FILE" | sort | uniq -c | sort -rn | head -10
        echo
        echo "AÇÃO: 403 body_inspection_failed = payload de ataque barrado na app."
        echo "  - Se for falso positivo legítimo: FOXCASH_BODY_INSPECTION_ENABLED=false"
        echo "    no Railway + afinar o padrão (deploy/edge.md §7.5)."
        echo "  - Se for ataque real: cruzar com X-Request-Id / edge (Cloudflare)."
    fi
fi

[ "$BLOCKS" -gt 0 ] && exit 2
exit 0
