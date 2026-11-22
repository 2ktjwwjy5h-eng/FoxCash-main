# Gerador de carga distribuído (Etapa 2 — Fase 2: 600→750/s).
#
# Projeto autocontido: deploy via `railway up ./loadgen --path-as-root -s loadgen`
# (o contexto de build é loadgen/; replicas via loadgen/railway.json). As
# variáveis do Railway
# (BASE_URL/STAGE/POOL/WEBHOOK_SECRET/MAX_VUS/DURATION/INSTANCE) viram __ENV
# no k6 (include-system-env-vars = true por padrão no `k6 run`).
#
# rate total = N réplicas × STAGE. Rede privada:
# BASE_URL=http://foxcash.railway.internal:8080 (porta ${PORT:8080} — :80 não
# responde; ver load-tests/k6/distributed-runbook.md).
FROM grafana/k6:latest

COPY capacity-stages.js /scripts/capacity-stages.js
COPY explosion-stages.js /scripts/explosion-stages.js
COPY probe.js /scripts/probe.js

WORKDIR /scripts

# k6 já é o ENTRYPOINT da imagem; define run <script> como comando padrão.
# Campanha 62 F9 (scale-out, 2026-08-09): CMD = capacity-stages.js (metodologia
# F7 — rate limit ELEVADO para medir o teto real; 1 venda = cobrança + webhook
# PAID). explosion-stages.js (F8) e probe.js (diagnóstico de conectividade)
# ficam disponíveis na imagem. Alterar CMD exige `railway up` (o redeploy reusa
# o config antigo). Escala dos geradores via `railway scale -s loadgen` — o
# STAGE por gerador é fixo (300/s) e a chegada total = N geradores × STAGE.
ENTRYPOINT ["k6", "run"]
CMD ["/scripts/capacity-stages.js"]
