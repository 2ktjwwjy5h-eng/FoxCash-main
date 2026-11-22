# backup-cron — serviço de backup 6/6h do PostgreSQL FoxCash (Spec 96 + RPO
# 6/6h da Spec 135).
#
# Roda ops/backup.sh a cada 6h DENTRO da rede privada do Railway (a
# DATABASE_URL do Postgres é injetada automaticamente no projeto). Build
# context = diretório ops/ (contém backup.sh, backup-upload.sh e este entrypoint).
#
# Base: postgres:18-alpine — pg_dump 18 igual à versão do servidor Railway
# (18.4). pg_dump de versão mais antiga NÃO consegue despejar um servidor mais
# novo (erro "server version mismatch"), então a base DEVE acompanhar a major
# do Postgres. `bash` é adicionado porque backup.sh e o entrypoint usam bash.
#
# Durabilidade: os dumps ficam em /backups (BACKUP_DIR). Para sobreviver a
# redeploys do contêiner, anexe um Volume do Railway em /backups
# (CLI: railway volume add --service backup-cron --mount-path /backups).
#
# Replicação fora da região (Spec 135/T2.2): rclone (binário único, sem deps)
# é instalado para o backup-upload.sh copiar o dump para um bucket S3-compatível
# (Cloudflare R2 — multi-região, egress grátis). O download é da release oficial
# (determinístico; a versão do pacote apk varia por release do Alpine).

FROM postgres:18-alpine

RUN apk add --no-cache bash curl unzip ca-certificates \
    && curl -fsSL https://downloads.rclone.org/rclone-current-linux-amd64.zip -o /tmp/rclone.zip \
    && unzip -o /tmp/rclone.zip -d /opt \
    && mv /opt/rclone-*-linux-amd64/rclone /usr/local/bin/rclone \
    && rclone version \
    && rm -rf /tmp/rclone.zip

COPY backup.sh /opt/backup.sh
COPY backup-upload.sh /opt/backup-upload.sh
COPY backup-cron-entrypoint.sh /opt/entrypoint.sh
RUN chmod +x /opt/backup.sh /opt/backup-upload.sh /opt/entrypoint.sh

ENV BACKUP_DIR=/backups \
    RETENTION_DAYS=14 \
    PG_BIN=/usr/lib/postgresql/18/bin

ENTRYPOINT ["/opt/entrypoint.sh"]
