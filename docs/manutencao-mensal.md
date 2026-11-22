# Checklist mensal — "não deixar cair" (operador)

Documento operacional de manutenção preventiva do FoxCash. Execute **1x por mês**
(ou após qualquer evento de billing/domínio). Tempo estimado: **~15 min**.

Riscos que este checklist cobre: billing (Railway/Cloudflare), renovação de
domínio (ICANN), certificados, health/readiness, drain de logs e jobs
agendados. **Nenhum desses falha por código — todos falham por fator
humano/burocrático.** Criado em 2026-08-20.

---

## 0. Prerequisitos

- CLI Railway instalada e autenticada (ex.: v5.30.4).
- Acesso ao dashboard do Cloudflare e do registrar do domínio `foxcash.example.com`.

---

## 1. Saúde da API (1 min)

```bash
curl -s https://api.foxcash.example.com/actuator/health
# esperado: {"status":"UP","groups":["liveness","readiness"]}

curl -s -o /dev/null -w "landing: %{http_code}\n" https://foxcash.example.com
# esperado: 200
```

Falhou? Ver `deploy/railway.md` (rollout/redeploy) e `deploy/edge.md` (DNS/proxy).

---

## 2. Billing Railway (3 min)

- **Abrir** https://railway.app/account → confirmar cartão **válido e não
  vencido** + método de pagamento ativo.
- **Histórico de cobrança**: confirmar que a cobrança do mês **passou** (sem
  recusa). Railway suspende o projeto silenciosamente após cobrança recusada —
  é o "cai sem aviso" clássico.
- **Plan/limite**: uso de build minutes e egress dentro do plano (em caso de
  dúvida, ver `docs/capacity-report.md`).

> Railway **não envia aviso com antecedência confiável** para cartão vencido —
> a conferência mensal é a defesa.

---

## 3. Billing Cloudflare + domínio (3 min)

- **Cloudflare**: dashboard → conta → billing → cartão válido (zona
  `foxcash.example.com` em plano free/pro).
- **Registrar do domínio** (`foxcash.example.com`): confirmar **auto-renew ATIVO** e
  data de expiração (ICANN — se vencer, **tudo** cai: landing, API, bots,
  webhook). Registrar atual: ver `deploy/edge.md` §Domínio.
- **DNS**: `dig foxcash.example.com` e `dig api.foxcash.example.com` resolvendo para os
  IPs esperados (Cloudflare).

> Domínio vencido = indisponibilidade total + risco de squatting. É o item de
> maior impacto do checklist.

---

## 4. Certificados (1 min)

- Railway renova Let's Encrypt automaticamente (~90 dias). Conferir que o
  site responde HTTPS válido:
  ```bash
  curl -sI https://foxcash.example.com | grep -i "HTTP\|strict-transport"
  ```
- Se aparecer erro de TLS, ver a "dança" do custom domain em `deploy/edge.md`.

---

## 5. Log drain (2 min)

- **Axiom** (`app.axiom.co` → dataset `foxcash`): confirmar que eventos chegam
  (ingest recente, `service=foxcash`). Se o dashboard do Axiom não estiver
  acessível, conferir via API:
  ```bash
  # 0 WARNs de ingest nas últimas horas = drain saudável:
  railway logs -p <PROJECT_ID> -e production -s FoxCash --since 2h \
    | grep -ci "Axiom ingest respondeu"
  # esperado: 0 (ou 1 se houve indisponibilidade — WARN é throttled a cada 5 min)
  ```
- Se o valor for alto, o drain está fora: ver `ops/log-drain.md` §2.2
  (dataset existe? token correto e escopado?).

---

## 6. Jobs agendados (2 min)

- **Monitor de body-inspection** (Agendador do Windows, tarefa `FoxCash Body
  Inspection Monitor`, */15): confirmar log recente:
  ```bash
  tail -1 logs/body-inspection-monitor.log
  # esperado: exit=0 {"alerts":0,"blocks":0,...}
  ```
- Confirmar a tarefa existe e habilitada:
  ```cmd
  schtasks /query /tn "FoxCash Body Inspection Monitor"
  ```
  Para recriar, ver `deploy/edge.md` §7.6 (comando `schtasks /create`).
- **Auto-withdraw / notificação / reconciliação de bot**: conferir nos logs do
  Railway que rodam sem erro (grep por `ERROR` nas últimas 24h):
  ```bash
  railway logs -p <PROJECT_ID> -e production -s FoxCash --since 24h \
    | grep -ci "ERROR"
  # esperado: 0 (ou poucos, pontuais)
  ```

---

## 7. Capacidade / alertas (2 min)

- **Postgres** (teto 500 conexões, `hikaricp_connections_active`): conferir
  métrica no Prometheus/actuator se disponível. Documentação: Spec 62/133.
- **Monitor de body-inspection**: se o log mostrar `blocks > 0`, investigar
  (pode ser ataque ou falso positivo — `deploy/edge.md` §7.6).
- **Alertas Discord do operador**: confirmar que o webhook do operador ainda
  entrega (spec 64 / `deploy/operator-bot.md`).

---

## 8. Segredos / rotação (1 min, opcional)

- Token `AXIOM_API_TOKEN` válido (se rotacionar, atualizar no Railway).
- API keys Cloudflare / PSP sem expiração iminente.
- Rotação de chaves: runbook em `docs/roteiro-melhorias-2026-08-10.md` §B.

---

## Resumo rápido (30s)

| # | Item | Comando / onde | OK? |
|---|---|---|---|
| 1 | Health | `curl api.foxcash.example.com/actuator/health` → UP | ☐ |
| 2 | Billing Railway | railway.app/account (cartão + cobrança) | ☐ |
| 3 | Domínio + Cloudflare | registrar (auto-renew) + billing CF | ☐ |
| 4 | Certificado | `curl -sI https://foxcash.example.com` → 200 TLS | ☐ |
| 5 | Drain Axiom | `railway logs ... | grep -c "Axiom ingest"` → 0 | ☐ |
| 6 | Cron monitor | `tail -1 logs/body-inspection-monitor.log` → exit=0 | ☐ |
| 7 | Errors 24h | `railway logs ... | grep -c ERROR` → 0 | ☐ |

**Regra de ouro:** se qualquer item falhar, resolva ANTES de fechar o mês —
não é "acumulável". A indisponibilidade total vem de itens 2 e 3 (billing +
domínio), que são os únicos que o sistema não se recupera sozinho.
