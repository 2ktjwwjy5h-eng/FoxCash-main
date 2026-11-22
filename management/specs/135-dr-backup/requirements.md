---
name: Multi-região / DR / backup restaurado provado
status: audited
references:
  - "management/roadmap.md#Fase 41"
  - "management/specs/96-backup-cron/requirements.md"
  - "deploy/railway.md"
---

# Spec 135 — Multi-região / DR / backup restaurado provado

## O que entrega

O due diligence apontou: **single-region (sfo), single-provider (Railway), sem
DR provado**. O backup existe (Spec 96: `ops/backup.sh` pg_dump -Fc + cron), mas
**nunca foi testado um restore real** (o `restore.sh` existe, o `backup-restore.md`
documenta — falta a PROVA). Uma multinacional exige: se a região cair, o sistema
volta em horas com dados íntegros; se o provedor falhar, há um caminho.

## Critérios de aceite

**C1 — Restore provado (o mais importante).** Executar um **DR drill real**: a
partir do backup mais recente (pg_dump -Fc da Spec 96), restaurar em um banco
vazio (staging) e validar: schema completo (todas as migrations), dados
presentes, app sobe e responde com o banco restaurado, reconciliação
wallet×ledger 0/0. Evidência registrada (data, tamanho do backup, tempo de
restore, resultado).

**C2 — RPO/RTO definidos e medidos.** RPO (perda máxima de dados): **≤ 4h
(backup 6/6h — decisão do usuário 2026-08-17)**; backup diário ≤ 24h era a
alternativa descartada. RTO (tempo de retorno): **medido no DR drill (C1) —
0.646s por 477 KB de dump (2026-08-17)** e documentado.
Backup **fora da região** do banco primário (bucket/armazenamento separado):
**ativo em produção 2026-08-17** — `ops/backup-upload.sh` (bucket S3-compatível
Cloudflare R2, multi-região) + integração no `backup.sh` + bucket
`foxcash-backups` + 6 envs `R2_*` no `backup-cron`; validado ao vivo (deploy
`a4119bf2`: objeto replicado no bucket).

**C3 — App em 2+ regiões (se viável).** Avaliar com evidência: replicar a app
(stateless, já pronta — Spec 61) em 2 regiões Railway com roteamento (DNS/edge).
O banco fica primário em 1 região; a app na região secundária lê/escreve no
primário (latência aceitável) ou vira standby. Se Railway não suportar
multi-região para o Postgres, documentar a limitação e a alternativa (multi-AZ
do provedor, ou infra híbrida). **Avaliação 2026-08-17 (T3.2 `[x]`)**: Railway
**não suporta** multi-região para o Postgres managed (single-region) — limitação
e alternativa documentadas em `deploy/railway.md` §6.1; **decisão: sem
active-active agora** (DR coberto por backup externo C2 + restore provado C1 +
redeploy rápido Spec 61). **T3.1 MEDIDO 2026-08-17** (probe em us-east, serviço
descartável deletado): rede privada funciona cross-região; `SELECT 1` conexão
nova **~443 ms** (us-east) vs ~36-40 ms (sfo); **warm/pool ~61 ms** vs ~2 ms —
custo steady-state ~+59 ms/query (charge: +300-360 ms/request).

**C4 — Runbook de DR.** `deploy/railway.md` + `ops/backup-restore.md` atualizados
com: passos de failover, quem faz, tempos esperados, contatos. O `restore.sh`
funciona de ponta a ponta (testado no drill).

**C5 — Teste automatizado do backup.** O backup cron (Spec 96) valida o
artefato após gerar (`pg_restore --list` ou `unzip -t` equivalente) — backup
corrompido não passa despercebido (mesma lição do L19 para o .m2, aplicada ao
backup).

## Evidência-base (2026-08-16)

- `ops/backup.sh` (pg_dump -Fc), `ops/restore.sh`, `ops/backup-restore.md`,
  `ops/install-backup-cron.sh` existem (Spec 96 audited).
- Deploy single-region: Railway sfo (status do serviço: `region: sfo`).
- App stateless (Spec 61) — pronta para multi-região sem mudança de código.

## Dependências

- **Spec 96** (backup cron) — base existente.
- **Spec 61** (HA/stateless) — pré-requisito multi-região já atendido.

## Fora de escopo

- PgBouncer/escala (Spec 133) — independente.
- Fila (Spec 134) — independente.
