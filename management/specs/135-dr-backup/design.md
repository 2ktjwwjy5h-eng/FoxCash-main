---
name: Multi-região / DR / backup restaurado provado
status: audited
---

# Spec 135 — Design: Multi-região / DR / backup restaurado provado

## D1 — Conceito

O due diligence apontou: **single-region (sfo), single-provider (Railway), sem
DR provado**. O backup existe (Spec 96: `ops/backup.sh` pg_dump -Fc + cron;
`ops/restore.sh`; `ops/backup-restore.md`), mas **nunca foi testado um restore
real**. Esta spec **prova** o restore (DR drill) e, se viável com evidência,
replica a app em 2+ regiões (a app é stateless — Spec 61 — pronta para isso).

## D2 — Restore provado (C1 — o mais importante)

**DR drill real** (executar e registrar evidência):
1. Pegar o backup mais recente (`ops/backup.sh` → `nyxcash_YYYY-MM-DD.dump`).
2. Restaurar num banco **vazio de staging** com `ops/restore.sh`
   (`pg_restore -Fc`).
3. Validar: schema completo (todas as migrations), dados presentes, a app
   **sobe e responde** com o banco restaurado, reconciliação wallet×ledger 0/0.
4. Registrar: data, tamanho do backup, tempo de restore, resultado (evidência
   em `ops/backup-restore.md` ou relatório dedicado).

**Gap identificado no script atual**: `ops/backup.sh` valida arquivo não vazio
e tamanho mínimo, mas **não valida integridade lógica** (C5 — mesma lição do
L19 aplicada ao backup): adicionar `pg_restore --list` (ou equivalente) **no
próprio backup.sh** após gerar o dump — backup corrompido não passa
despercebido.

## D3 — RPO/RTO definidos e medidos (C2)

- **RPO**: decidir com o usuário — backup diário → RPO ≤ 24h; se necessário
  ≤ 4h, agendar backup 6/6h (`install-backup-cron.sh`). **Backup fora da
  região** do banco primário (bucket/armazenamento separado — Railway
  volumes/bucket externo S3-compatível).
  **Implementado (2026-08-17):** RPO 6/6h ≤ 4h decidido; replicação externa via
  `ops/backup-upload.sh` (rclone → bucket S3-compatível, padrão Cloudflare R2),
  env-gated por `R2_BUCKET` no `backup.sh` (falha na replicação falha o backup).
- **RTO**: medido no DR drill (C1) e documentado (tempo de restore + boot da
  app + validação).

## D4 — App em 2+ regiões (C3 — se viável, com evidência)

- A app é **stateless** (Spec 61) — replicar em 2 regiões Railway (ex.: sfo +
  outro datacenter) com roteamento (DNS/edge — Cloudflare, Spec 63/ADR-014).
- **Banco**: primário em 1 região; a app da região secundária lê/escreve no
  primário (latência aceitável) **ou** vira standby. Se o Railway não suportar
  multi-região para o Postgres: documentar a limitação + alternativa
  (multi-AZ do provedor ou infra híbrida).
- **Decisão com evidência**: benchmark/medição de latência da região
  secundária → primário; se > limiar aceitável, documentar e não forçar.
  **Avaliação 2026-08-17 (T3.2 `[x]`):** Railway **não suporta** multi-região
  para o Postgres managed (single-region) → limitação + alternativa documentadas
  em `deploy/railway.md` §6.1; decisão: sem active-active agora (DR coberto por
  backup externo + restore provado + redeploy Spec 61); T3.1 (medição ao vivo)
  = operação de produção pendente.

## D5 — Runbook de DR (C4)

- `deploy/railway.md` + `ops/backup-restore.md` atualizados: passos de
  failover, quem faz, tempos esperados, contatos. `ops/restore.sh` funciona de
  ponta a ponta (testado no drill).

## D6 — Teste automatizado do backup (C5)

- `ops/backup.sh` valida o artefato após gerar (`pg_restore --list`) — backup
  corrompido falha na hora (não só tamanho mínimo).

## D7 — Arquivos

| Arquivo | Mudança |
|---|---|
| `ops/backup.sh` | validação `pg_restore --list` pós-dump (D2/D6) |
| `ops/backup-restore.md` | evidência do DR drill + RPO/RTO + runbook (D2/D3/D5) |
| `ops/install-backup-cron.sh` | (se RPO ≤ 4h) agenda 6/6h (D3) |
| `deploy/railway.md` | multi-região (se aplicável) + runbook (D4/D5) |
| infra (Railway/Cloudflare) | 2ª região + roteamento (D4, se viável) |

## D8 — Fora de escopo

PgBouncer/escala (Spec 133); fila (Spec 134); réplicas de leitura (Spec
62/ADR-013 — permanece dispensado).

**Âncoras validadas na spec:** `ops/backup.sh` (pg_dump -Fc, retenção 14d,
flock, valida tamanho mínimo); `ops/restore.sh`; `ops/backup-restore.md`;
`ops/install-backup-cron.sh` (Spec 96 audited); app stateless (Spec 61);
Cloudflare edge (Spec 63/ADR-014); deploy single-region Railway sfo.
