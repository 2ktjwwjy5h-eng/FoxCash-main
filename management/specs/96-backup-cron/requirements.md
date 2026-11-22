---
name: Backup automático — instalar/verificar o cron
status: audited
references:
  - "management/vision.md#V72"
  - "ops/backup-restore.md"
  - "ops/backup.sh"
  - "ops/restore.sh"
---

# Spec 96 — Backup automático: instalar/verificar o cron (lacuna #10)

## O que entrega

Automatiza e **verifica** a rotina de backup diário do PostgreSQL
(`ops/backup.sh`, já validado em 2026-08-07). Hoje o agendamento depende de
**instalação manual** (`crontab -e`) — se o operador esqueceu, o backup não
roda e **ninguém detecta**. A spec entrega:

1. **`ops/install-backup-cron.sh`** — instalador/verificador do cron:
   - **`install`**: adiciona a linha `30 2 * * * <abs>ops/backup.sh >>
     /var/log/nyxcash-backup.log 2>&1` no crontab de forma **idempotente** (não
     duplica); se `crontab` não existir (Windows/Git Bash), imprime as
     instruções alternativas (Task Scheduler/WSL) e falha de forma clara.
   - **`check`**: verifica — (a) linha do cron presente; (b) `backup.sh`
     executável; (c) **frescura do último dump** (< 2 dias — falha silenciosa do
     cron deixa o dump velho); (d) retenção de backups ok. Exit 0 se tudo ok,
     exit 1 com diagnóstico claro caso contrário.
   - **`uninstall`**: remove a linha do cron (idempotente).
2. **`ops/logrotate-nyxcash-backup.conf`** — rotativo do log
   `/var/log/nyxcash-backup.log` (60 dias — fecha a promessa "rotativo simples"
   do runbook, que hoje não tem implementação).
3. **Atualização do `ops/backup-restore.md`** — usa o instalador (em vez do
   `crontab -e` manual), adiciona o **checklist de verificação** (frescura,
   cron presente, restore testado periodicamente) e o procedimento de
   **teste de restore** (já validado em 2026-08-07, agora com passo de
   agendamento).

**Não entrega:** alteração no `backup.sh`/`restore.sh` (já validados — sem
mudança); nenhum código Java/Spring (spec 100% operacional).

> **Aditivo pós-auditoria (commit `e6863d6`):** para produção no Railway, em vez
> do cron do host, a spec passou a entregar também o **serviço `backup-cron`**
> (contêiner em `ops/` — `Dockerfile` `postgres:18-alpine` +
> `backup-cron-entrypoint.sh`) que roda `backup.sh` a cada 24h **na rede privada
> do Railway** (interpreta a `DATABASE_URL` injetada → `DB_*`; falha de um ciclo
> não mata o loop). Documentado em `ops/backup-restore.md` + `deploy/railway.md`
> §6. Continua sem código Java — é operacional.

## Contexto (por que agora)

Auditoria de segurança 2026-08-14 (lacuna #10): os scripts e o runbook de DR
existem e foram validados, mas o **agendamento é manual e não verificado** —
o RPO/RTO não é garantido por cron. Com o instalador + `check` (frescura),
o operador **instala em 1 comando e detecta falha silenciosa** antes de precisar
do backup. É o item operacional que fecha o checklist 8/10 (com a #9 decidida
no ADR-018 e a #7 entregue na Spec 95).

## Critérios de aceite

1. **Instalador idempotente** — `./ops/install-backup-cron.sh install` adiciona
   a linha `30 2 * * *` do `backup.sh` no crontab; rodar de novo **não duplica**
   (exit 0 "já instalado"). Caminho do script resolvido absolutamente (funciona
   de qualquer diretório).
2. **Modo `check`** — `./ops/install-backup-cron.sh check` valida: (a) linha do
   cron presente; (b) `backup.sh` executável; (c) último dump
   `nyxcash_*.dump` com mtime < 2 dias; (d) dir de backup legível. Exit 0 se
   tudo ok; exit 1 listando exatamente o que falhou (saída legível por
   humano/agente).
3. **Modo `uninstall`** — remove a linha (idempotente; ausente → exit 0).
4. **Sem `crontab`** (Windows/Git Bash) — `install` falha com mensagem clara e
   instruções alternativas (Task Scheduler/WSL); não corrompe nada.
5. **Logrotate** — `ops/logrotate-nyxcash-backup.conf` rotaciona
   `/var/log/nyxcash-backup.log` com retenção de 60 dias; o instalador instala
   no `/etc/logrotate.d/` quando tem permissão (senão documenta).
6. **Runbook atualizado** — `ops/backup-restore.md` usa o instalador e ganha o
   **checklist de verificação** (diário: `check`; mensal: teste de restore em
   banco descartável; pós-falha: restaurar o mais recente e validar).
7. **Validação** — `bash -n` limpo nos scripts; `install`/`check`/`uninstall`
   testados num crontab descartável (ou com `crontab` indisponível → fallback
   de diagnóstico testado).

## Dependências

- **`ops/backup.sh`** (já validado) — o instalador só agenda; não muda o script.
- **`ops/backup-restore.md`** (runbook de DR validado em 2026-08-07).

## Fora de escopo

- **Backup do Railway Postgres** (snapshots gerenciados) — a spec cobre o dump
  via `backup.sh` no host/worker; o snapshot do Railway é opção do painel
  (já documentada em `deploy/railway.md` §6).
- **Instalar o cron de fato no Railway** — é o passo operacional do operador
  (acesso ao host); a spec entrega o script + checklist.
- **DR multi-região / cópia offsite do dump** — spec futura se o volume
  justificar.
