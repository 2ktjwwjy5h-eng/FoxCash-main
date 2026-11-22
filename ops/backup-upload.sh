#!/usr/bin/env bash
#
# backup-upload.sh — envia o dump para um bucket S3-compatível FORA da região do
# banco primário (DR — Spec 135/T2.2).
#
# O dump em /backups (volume Railway) é do MESMO provedor/região do banco — se o
# provedor ou a região cair, o backup cai junto. Este script replica o dump para
# um bucket externo, multi-região por default.
#
# Padrão: Cloudflare R2 (o domínio já está no Cloudflare — Spec 63; R2 é
# multi-região por default, egress grátis, API S3-compatível). Funciona com
# QUALQUER bucket S3-compatível (AWS S3, Backblaze B2, MinIO...) — basta trocar
# R2_ENDPOINT e as credenciais.
#
# Uso:
#   ./ops/backup-upload.sh <arquivo.dump>
#
# Config via env (nunca no repo — painel do provedor/Railway):
#   R2_ENDPOINT            (obrigatório) — ex.: https://<accountid>.r2.cloudflarestorage.com
#   R2_BUCKET              (obrigatório) — nome do bucket (criar no painel R2)
#   R2_ACCESS_KEY_ID       (obrigatório) — token de API R2 (permissão Object Read/Write)
#   R2_SECRET_ACCESS_KEY   (obrigatório)
#   R2_PREFIX              (default: foxcash) — "pasta" no bucket
#   R2_RETENTION_DAYS      (default: 14) — apaga objetos mais velhos no bucket
#
# Requer: rclone instalado (o Dockerfile do backup-cron já instala). No host
# Linux: instalar rclone (apk add rclone / apt install rclone / binário oficial).
#
# Sai com exit != 0 em QUALQUER falha — o backup.sh (que chama este script)
# marca o backup como falho e o cron/entrypoint alarma (Spec 135: um backup que
# não foi replicado fora da região não cumpre a promessa de DR).
#
# Segurança: as credenciais só existem como variáveis de ambiente — NUNCA são
# gravadas em disco (rclone via flags, sem config file).

set -euo pipefail

PREFIX_LOG="[backup-upload]"

# ---------------------------------------------------------------------------
# Argumentos / config
# ---------------------------------------------------------------------------

FILE="${1:-}"
if [ -z "$FILE" ]; then
    echo "$PREFIX_LOG uso: $0 <arquivo.dump>" >&2
    exit 1
fi
if [ ! -f "$FILE" ]; then
    echo "$PREFIX_LOG arquivo não encontrado: $FILE" >&2
    exit 1
fi

: "${R2_ENDPOINT:?$PREFIX_LOG R2_ENDPOINT obrigatório (ex.: https://<accountid>.r2.cloudflarestorage.com)}"
: "${R2_BUCKET:?$PREFIX_LOG R2_BUCKET obrigatório}"
: "${R2_ACCESS_KEY_ID:?$PREFIX_LOG R2_ACCESS_KEY_ID obrigatório}"
: "${R2_SECRET_ACCESS_KEY:?$PREFIX_LOG R2_SECRET_ACCESS_KEY obrigatório}"

R2_PREFIX="${R2_PREFIX:-foxcash}"
R2_RETENTION_DAYS="${R2_RETENTION_DAYS:-14}"

if ! command -v rclone >/dev/null 2>&1; then
    echo "$PREFIX_LOG rclone não encontrado no PATH (instale ou use o contêiner backup-cron do Railway)." >&2
    exit 1
fi

REMOTE=":s3:${R2_BUCKET}/${R2_PREFIX}"
BASENAME="$(basename "$FILE")"

# Flags comuns do rclone para o S3-compatible (Cloudflare R2).
RCLONE_S3_ARGS=(
    --s3-provider Cloudflare
    --s3-endpoint "$R2_ENDPOINT"
    --s3-access-key-id "$R2_ACCESS_KEY_ID"
    --s3-secret-access-key "$R2_SECRET_ACCESS_KEY"
    --s3-region auto
    --s3-no-check-bucket
)

# ---------------------------------------------------------------------------
# Upload
# ---------------------------------------------------------------------------

echo "$PREFIX_LOG enviando ${BASENAME} -> ${R2_BUCKET}/${R2_PREFIX}/ (${R2_ENDPOINT})"
rclone copy "$FILE" "$REMOTE" "${RCLONE_S3_ARGS[@]}"

# ---------------------------------------------------------------------------
# Verificação — o objeto existe no bucket com o tamanho certo?
# (mesma lição do C5: backup corrompido/ausente não passa despercebido)
# ---------------------------------------------------------------------------

LOCAL_SIZE="$(stat -c %s "$FILE" 2>/dev/null || echo 0)"
if ! rclone lsl "$REMOTE" "${RCLONE_S3_ARGS[@]}" 2>/dev/null \
        | grep -E "^ *${LOCAL_SIZE} +.*$(printf '%s' "$BASENAME" | sed 's/[.[\*^$()+?{|]/\\&/g')$" \
        >/dev/null; then
    echo "$PREFIX_LOG FALHA: objeto ausente ou tamanho divergente no bucket (${BASENAME}, ${LOCAL_SIZE} bytes)." >&2
    exit 1
fi
echo "$PREFIX_LOG OK: ${BASENAME} replicado no bucket (${LOCAL_SIZE} bytes) fora da região do banco."

# ---------------------------------------------------------------------------
# Retenção no bucket (best-effort — falha NÃO falha o backup, igual ao local)
# ---------------------------------------------------------------------------

if [ "$R2_RETENTION_DAYS" -gt 0 ] 2>/dev/null; then
    rclone delete "$REMOTE" --min-age "${R2_RETENTION_DAYS}d" "${RCLONE_S3_ARGS[@]}" 2>/dev/null \
        && echo "$PREFIX_LOG retenção: removidos objetos com mais de ${R2_RETENTION_DAYS} dias no bucket" \
        || echo "$PREFIX_LOG aviso: falha ao limpar objetos antigos no bucket (ignorado)"
fi

echo "$PREFIX_LOG concluído."
