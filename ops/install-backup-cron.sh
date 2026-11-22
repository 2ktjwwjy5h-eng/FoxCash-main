#!/usr/bin/env bash
#
# install-backup-cron.sh — instala/verifica/remove o cron do backup diário do
# PostgreSQL FoxCash (Spec 96 — lacuna #10 da auditoria 2026-08-14).
#
# O agendamento manual (`crontab -e`) não era verificado — uma falha silenciosa
# do cron deixava o dump velho sem ninguém perceber. Este script:
#   install    (default) — agenda `30 2,8,14,20 * * *` (RPO 6/6h — Spec 135) de
#                forma IDEMPOTENTE;
#   check      — valida cron presente + backup.sh executável + frescura do dump
#                (< MAX_DUMP_AGE_HOURS, default 5h — abaixo da janela de 6h) +
#                dir legível;
#   uninstall  — remove a linha (idempotente).
#
# Uso:
#   ./ops/install-backup-cron.sh [install|check|uninstall]
#
# Config via env:
#   BACKUP_DIR   (default /var/backups — onde o backup.sh grava)
#   CRON_SCHEDULE(default "30 2,8,14,20 * * *" — RPO 6/6h: 4 backups/dia)
#   LOG_FILE     (default /var/log/nyxcash-backup.log)
#   MAX_DUMP_AGE_HOURS (default 5)
#
# Windows (Git Bash sem crontab): o install falha com instruções alternativas
# (WSL `crontab` ou Task Scheduler) — nada é corrompido.

set -euo pipefail

PREFIX="[backup-cron]"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_SCRIPT="$SCRIPT_DIR/backup.sh"
BACKUP_DIR="${BACKUP_DIR:-/var/backups}"
CRON_SCHEDULE="${CRON_SCHEDULE:-30 2,8,14,20 * * *}"
LOG_FILE="${LOG_FILE:-/var/log/nyxcash-backup.log}"
MAX_DUMP_AGE_HOURS="${MAX_DUMP_AGE_HOURS:-5}"

CRON_LINE="$CRON_SCHEDULE \"$BACKUP_SCRIPT\" >> $LOG_FILE 2>&1"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

log()  { echo "$PREFIX $*"; }
fail() { echo "$PREFIX ERRO: $*" >&2; exit 1; }

# Linhas atuais do crontab (vazio se não houver). Nunca falha com pipefail.
current_crontab() {
    if command -v crontab >/dev/null 2>&1; then
        crontab -l 2>/dev/null || true
    fi
}

crontab_available() {
    command -v crontab >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# install
# ---------------------------------------------------------------------------
do_install() {
    if [ ! -x "$BACKUP_SCRIPT" ]; then
        fail "backup.sh não encontrado/executável em: $BACKUP_SCRIPT"
    fi
    if ! crontab_available; then
        cat >&2 <<EOF
$PREFIX ERRO: comando 'crontab' indisponível neste ambiente (provavelmente Git Bash no Windows).
Instale o cron em uma destas alternativas:
  1. WSL (Linux): rode este script dentro do WSL — 'crontab' existe.
  2. Windows Task Scheduler: agende 4×/dia (02:30/08:30/14:30/20:30 UTC) executando:
       bash "$BACKUP_SCRIPT" >> "$LOG_FILE" 2>&1
Nenhuma alteração foi feita.
EOF
        exit 1
    fi

    local current
    current="$(current_crontab)"
    if printf '%s\n' "$current" | grep -Fq "$CRON_LINE"; then
        log "cron já instalado (idempotente) — nenhuma mudança:"
        log "  $CRON_LINE"
        maybe_install_logrotate
        exit 0
    fi

    # Preserva as entradas existentes; adiciona a linha nova.
    { printf '%s\n' "$current"; echo "$CRON_LINE"; } | crontab -
    log "cron instalado:"
    log "  $CRON_LINE"
    maybe_install_logrotate
    log "verifique com: ./ops/install-backup-cron.sh check"
}

# ---------------------------------------------------------------------------
# check
# ---------------------------------------------------------------------------
do_check() {
    local ok=0

    # (a) linha do cron presente
    if crontab_available; then
        if printf '%s\n' "$(current_crontab)" | grep -Fq "$CRON_LINE"; then
            log "[ok] cron instalado: $CRON_LINE"
        else
            log "[FALHA] cron NÃO instalado — rode: ./ops/install-backup-cron.sh install"
            ok=1
        fi
    else
        log "[FALHA] crontab indisponível (Windows?) — agende via WSL/Task Scheduler (ver install)"
        ok=1
    fi

    # (b) backup.sh executável
    if [ -x "$BACKUP_SCRIPT" ]; then
        log "[ok] backup.sh executável: $BACKUP_SCRIPT"
    else
        log "[FALHA] backup.sh não encontrado/executável: $BACKUP_SCRIPT"
        ok=1
    fi

    # (c) frescura do último dump (< MAX_DUMP_AGE_HOURS)
    if [ -d "$BACKUP_DIR" ]; then
        local latest
        latest="$(ls -t "$BACKUP_DIR"/foxcash_*.dump 2>/dev/null | head -1 || true)"
        if [ -n "$latest" ] && [ -f "$latest" ]; then
            local age_hours
            age_hours=$(( ($(date +%s) - $(stat -c %Y "$latest" 2>/dev/null || echo 0)) / 3600 ))
            if [ "$age_hours" -le "$MAX_DUMP_AGE_HOURS" ]; then
                log "[ok] último dump fresco (${age_hours}h <= ${MAX_DUMP_AGE_HOURS}h): $latest"
            else
                log "[FALHA] último dump VELHO (${age_hours}h > ${MAX_DUMP_AGE_HOURS}h): $latest — o cron pode ter falhado silenciosamente"
                ok=1
            fi
        else
            log "[FALHA] nenhum backup em $BACKUP_DIR — rode: ./ops/backup.sh"
            ok=1
        fi
    else
        log "[FALHA] dir de backup não existe: $BACKUP_DIR"
        ok=1
    fi

    if [ "$ok" -eq 0 ]; then
        log "check OK — rotina de backup saudável."
    else
        log "check com falhas — corrija os itens acima."
    fi
    exit "$ok"
}

# ---------------------------------------------------------------------------
# uninstall
# ---------------------------------------------------------------------------
do_uninstall() {
    if ! crontab_available; then
        log "crontab indisponível — nada a remover."
        exit 0
    fi
    local current filtered
    current="$(current_crontab)"
    filtered="$(printf '%s\n' "$current" | grep -Fv "$CRON_LINE" || true)"
    if [ "$filtered" = "$current" ]; then
        log "cron não estava instalado (idempotente)."
    else
        printf '%s\n' "$filtered" | crontab -
        log "cron removido: $CRON_LINE"
    fi
}

# ---------------------------------------------------------------------------
# logrotate (best-effort — falha não falha o install)
# ---------------------------------------------------------------------------
maybe_install_logrotate() {
    local conf="/etc/logrotate.d/nyxcash-backup"
    if [ -d /etc/logrotate.d ] && [ -w /etc/logrotate.d ] && [ -f "$SCRIPT_DIR/logrotate-nyxcash-backup.conf" ]; then
        cp "$SCRIPT_DIR/logrotate-nyxcash-backup.conf" "$conf"
        log "logrotate instalado em $conf"
    elif [ -d /etc/logrotate.d ] && [ ! -w /etc/logrotate.d ]; then
        log "aviso: /etc/logrotate.d não gravável — instale manualmente: sudo cp ops/logrotate-nyxcash-backup.conf /etc/logrotate.d/"
    fi
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
MODE="${1:-install}"
case "$MODE" in
    install)   do_install ;;
    check)     do_check ;;
    uninstall) do_uninstall ;;
    *) fail "modo inválido: $MODE (use install|check|uninstall)" ;;
esac
