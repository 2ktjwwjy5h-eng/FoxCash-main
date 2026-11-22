# Resposta a Incidente de Segurança — runbook (Spec 64)

> Procedimento **detectar → conter → mitigar → restaurar** para as classes de
> ataque que o FoxCash enfrenta. Reusa os runbooks validados: backup/restore
> (`ops/backup-restore.md`), deploy/HA (`deploy/railway.md`), edge/WAF/bot
> (`management/specs/63-edge-waf-bot-mitigation/` — Cloudflare).
>
> **Filosofia (Spec 64/16):** os alertas são **indicadores** — a decisão e a
> ação continuam humanas/auditadas (Specs 37/41). Sempre que uma ação destrutiva
> for necessária (bloquear lojista, invalidar chave, banir IP), registre na
> trilha (`platform_activity_log`) e avise o operador.

## Visão geral

| Item | Valor |
|---|---|
| Canal de alerta | Webhook de operador (`FOXCASH_NOTIFICATION_OPERATOR_WEBHOOK_URL` + `_SECRET`) — evento de plataforma assinado HMAC-SHA256; **sem config → fallback log** (fail-safe, Spec 53) |
| Eventos de segurança | `LOGIN_FAILURE_BURST`, `RATE_LIMIT_BURST`, `SIGNATURE_FAILURE_BURST`, `RATE_LIMIT_FAIL_OPEN`, `BODY_INSPECTION_BLOCKED` (Spec 63 camada app + Spec 64) + `JOB_FAILED`/`OUTBOX_DEAD` (Specs 53/54) |
| Limiares/janelas | `foxcash.security.alert.*` (env `FOXCASH_SECURITY_ALERT_*`) — defaults: login 10/60s, 429 20/60s, assinatura 20/60s, inspeção de corpo 5/60s, throttle 300s |
| Chave de investigação | `X-Request-Id` (cada request) — cruzar logs → `platform_activity_log` → métricas (Spec 57) |
| Responsável | Operador de plantão (via webhook de operador) — documento com contatos em `deploy/railway.md` |

**Regras gerais antes de qualquer classe:**

1. **Não reaja no susto**: verifique o alerta (evidência nos logs/outbox) antes
   de bloquear. Alerta é indicador.
2. **Priorize a disponibilidade**: o gateway de pagamento **não pode parar de
   aceitar tráfego** por ação de resposta (ADR-010 — fail-open é regra, não bug).
3. **Tudo auditável**: qualquer ação em conta (suspensão, bloqueio, rotação de
   chave) entra na trilha (`platform_activity_log`) e/ou changelog.
4. **Anote o incidente**: salve `X-Request-Id` de exemplo, janela de tempo
   (UTC) e o que foi feito — é o insumo do teste de mesa e da melhoria do
   runbook.

---

## Classe 1 — Flood / DoS

**Detectar**

- Alerta `RATE_LIMIT_BURST` (grupo `transactional`, `webhook`, ou rota pública)
  ou `OUTBOX_DEAD` em cascata;
- Métricas: `http_server_requests_seconds_count{status=~"5.."}` subindo,
  `hikaricp_connections_pending > 0`, heap subindo (Grafana Cloud — opcional,
  Spec 64 §4);
- Readiness DOWN (Spec 55) ou erros 500 em massa;
- No edge (Cloudflare): analytics mostrando pico de requests de um IP/ASN.

**Conter** (prioridade: parar o sangramento sem derrubar o serviço)

1. Confirmar no dashboard do Cloudflare que o edge está **proxy ON** e o WAF
   ativo (Spec 63) — o flood deve ser absorvido na borda.
2. Se o edge rate limit não estiver cortando: **adicionar/ajustar regra de rate
   limit por IP/ASN** na rota atacada (Spec 63 §4) — ex.: rota de login 10/min,
   rotas transacionais 300/min (baseline do app, Spec 58).
3. Se necessário: **bloquear IP/ASN/UA na borda** (regra customizada do WAF —
   Cloudflare) — o app nem vê o request.
4. **Não escalar por reflexo**: primeiro corte na borda; só então, se o pico for
   legítimo (ex.: pico de vendas), escalar (replicas/plan — `deploy/railway.md`).

**Mitigar**

- Identificar a origem (logs do edge + `X-Request-Id` do app): IP/ASN, rota,
  padrão de payload.
- Decidir se é ataque (bloquear na borda) ou carga legítima (escalar).
- Se a app degradou (Hikari/threads): reiniciar instância afetada
  (`deploy/railway.md` — redeploy com healthcheck).

**Restaurar**

- Remover bloqueios de borda após o fim do ataque (janela definida — ex.: 1h).
- Verificar health/readiness e que o 5xx rate voltou ao normal.
- Registrar o incidente (o que foi bloqueado, quando, evidências).

---

## Classe 2 — Automação de conta / brute-force

**Detectar**

- Alerta `LOGIN_FAILURE_BURST` (source `master` ou `merchant`);
- Alerta `BODY_INSPECTION_BLOCKED` (payload de ataque — SQLi/XSS/path traversal —
  barrado na app antes da auth; Spec 63 camada app);
- Lockouts em massa: `platform_activity_log` com várias contas
  `LOCKED`/`MFA_LOCKED` no mesmo período;
- Métricas: pico de 401 nas rotas de login/forgot/reset;
- Turnstile rejeitando sem token válido (Spec 63 §5) — automação sem widget.

**Conter**

1. Confirmar que o **Turnstile está ativo** no portal (login/cadastro) — é a
   primeira barreira contra automação (Spec 63 §5); se desligado, religar.
2. Rate limit por IP já cobre as rotas públicas de login (Spec 18) — verificar
   se a regra do edge complementa (login 10/min por IP).
3. **Não desbloquear contas automaticamente**: o lockout expira sozinho
   (Spec 44) — contas legítimas voltam após a janela.
4. Se o ataque persiste mesmo com Turnstile: bloquear IP/ASN na borda (Classe 1).

**Mitigar**

- Identificar as contas-alvo (`platform_activity_log` — buscar por `LOCKED` no
  período) e o padrão de origem (IP/ASN via edge).
- **Invalidar chaves suspeitas** (Spec 24 — API key): se o atacante tem posse
  de chave vazada, revogar e emitir nova (auditado na trilha).
- Suspender cadastro? — só com decisão do operador (sem auto-ação, Spec 64).

**Restaurar**

- Após o fim do ataque: liberar contas legítimas afetadas (auditoria de quem
  foi bloqueado — Spec 37/41), aguardando expiração natural do lockout.
- Revisar se o limiar de `LOGIN_FAILURE_BURST` está adequado (falso positivo
  alto → ajustar `foxcash.security.alert.login-failure.threshold`).

---

## Classe 3 — Webhook replay / spoofing

**Detectar**

- Alerta `SIGNATURE_FAILURE_BURST` (path `/webhooks/pix` ou `/webhooks/payouts`);
- Logs do `WebhookSignatureFilter`: `Webhook rejeitado: assinatura ausente ou
  invalida` (throttled — Spec 59) com path e header;
- Replays: eventos de idempotência (Specs 28/36) — mesma `externalReference`
  reentregue fora do padrão do PSP.

**Conter**

1. **Verificar o segredo da rota** (`foxcash.psp.webhook.secret` /
   `payout-webhook.secret`): foi rotacionado/exposto? Compare com o configurado
   no PSP (Pagar.me) — assinatura deve bater para requests legítimos.
2. Se suspeita de **spoofing ativo** (não é erro de config): bloquear IP/ASN na
   borda na rota `/webhooks/*` (edge rate limit — Spec 59 já limita por IP;
   reforçar na borda).
3. **Revogar/rotacionar o segredo** se houver indício de vazamento (Spec 35) —
   atualizar no PSP e na env var, **nunca** em yml (fail-fast sem segredo).

**Mitigar**

- Investigar a origem dos 401: IP/ASN (edge), user-agent, padrão de payload,
  `X-Request-Id` dos requests rejeitados.
- Confirmar que **nenhuma transação foi aprovada sem assinatura válida** (a
  rejeição é pré-processamento — o filtro 401 acontece antes do controller,
  Spec 10/11/36) — conferir `pix_transactions` no período.
- Se replay real detectado: revisar idempotência (Specs 28/36) e, se necessário,
  rodar backup/restore pontual (decisão do operador — `ops/backup-restore.md`).

**Restaurar**

- Restaurar o segredo correto (se rotacionado) e **re-testar**: enviar um
  webhook de teste assinado (homologação — `deploy/psp-homologation.md`) → 204.
- Remover bloqueios temporários de borda após confirmar que o tráfego do PSP é
  legítimo (a reentrega do PSP é em minutos — não bloquear por muito tempo).

---

## Classe 4 — Chave/segredo vazado (API key, token, env var)

**Detectar**

- Trilha: `platform_activity_log` com ações de uma conta em horários/IPs
  anômalos (Spec 41);
- Métricas/uso: pico de requests autenticados de uma única chave/conta
  (dashboard por chave — Spec 58 limita por principal, mas o volume chama
  atenção);
- `MERCHANT_LOGIN` de locais estranhos + `RATE_LIMIT_BURST` (grupo
  `authenticated`) na mesma conta.

**Conter**

1. **Rotacionar imediatamente** a credencial suspeita:
   - API key do lojista: revogar + emitir nova (Spec 24) — auditado na trilha;
   - Secret/env var (webhook, PSP, master seed): rotacionar no PSP/Railway e
     atualizar env — nunca commit de segredo.
2. **Suspender a conta** se o uso é claramente malicioso (Spec 16 — suspensão
   por política de uso) — decisão do operador.
3. Se o vazamento é de credencial de **master**: rotacionar senha + MFA
   (Spec 44), revogar sessões.

**Mitigar**

- Auditoria de acesso: o que a credencial fez desde o vazamento? (trilha +
  `job_runs` + métricas por `X-Request-Id`).
- Revisar transações/ledger da conta afetada; se houve saque indevido, seguir o
  runbook de DR apenas se necessário (`ops/backup-restore.md` — dados são a
  única cópia).
- Verificar se o segredo vazado aparece em git history/artefatos (gitleaks/scan)
  — se sim, rotacionar e purgar.

**Restaurar**

- Emitir novas credenciais para o lojista legítimo (após prova de identidade —
  auditoria, Spec 37).
- Monitorar a conta por alguns dias (reincidência).

---

## Classe 5 — Abuso de lojista (uso indevido da plataforma)

**Detectar**

- Trilha + dashboards: volume anômalo de `CHARGE_CREATED`/`SALE_PAID`,
  padrão de `DELIVERY_ACTION_COMPLETED` estranho, saques fora do padrão;
- `RATE_LIMIT_BURST` (grupo `authenticated`) persistente da mesma conta;
- Métricas de uma chave muito acima do baseline.

**Conter**

1. **Suspender a conta** (Spec 16 — política de uso; decisão do operador,
   auditada na trilha com motivo).
2. **Bloquear saque** da conta (Spec 30/31 — controle de saque) enquanto
   investiga.
3. Limitar a chave na borda/app se o abuso é de API (rate limit por principal
   já ativo — Spec 58; reforçar limiar se necessário).

**Mitigar**

- Investigar transações/ledger da conta no período: origem dos pagamentos,
   chargebacks/refunds (Spec 16 §4), padrão de uso.
- Cruzar com `platform_activity_log` e `X-Request-Id` para reconstruir o que
   foi feito.

**Restaurar**

- Decisão do operador: liberar (se foi uso legítimo — desfazer suspensão,
   auditado), ou encerrar (manter suspensão/bloqueio).
- Registrar a decisão e o motivo na trilha (Spec 41).

---

## Teste de mesa (Spec 64 — critério 7)

> Executar com um operador/agente seguindo o runbook **sem acesso ao código**.
> Simular o incidente (dados falsos/isolados), anotar cada passo, corrigir gaps
> no runbook. Evidência: tabela preenchida.

### Mesa 1 — Flood/DoS (Classe 1)

**Simulação:** 200 requests/min de um IP único na rota `/api/v1/merchant-auth/login`
com 401 (senha errada). Alerta esperado: `LOGIN_FAILURE_BURST` + `RATE_LIMIT_BURST`
(429 do app).

| Passo do runbook | Executado? | Resultado / gap |
|---|---|---|
| 1. Confirmar edge proxy ON + WAF | Sim | Cloudflare absorveu parte; app viu o resto (edge rate limit por IP ajustado) |
| 2. Ajustar edge rate limit na rota | Sim | 10/min/IP no login → cortou antes do app |
| 3. Bloquear IP/ASN na borda | Sim | Regra customizada aplicada em <1min |
| 4. Verificar health pós-bloqueio | Sim | 5xx voltou a 0; readiness OK |
| **Gap encontrado** | — | **Falta alerta do edge → operador** (Cloudflare notifications não configuradas) — anotado na Spec 63 |

### Mesa 2 — Chave vazada (Classe 4)

**Simulação:** API key de um lojista usada de outro IP (volume 5× o baseline) em
rotas transacionais. Alerta esperado: `RATE_LIMIT_BURST` (grupo `authenticated`)
ou uso anômalo na trilha.

| Passo do runbook | Executado? | Resultado / gap |
|---|---|---|
| 1. Localizar conta/trilha | Sim | `platform_activity_log` mostrou `CHARGE_CREATED` de IP estranho (evidência: `X-Request-Id`) |
| 2. Rotacionar API key (Spec 24) | Sim | Chave revogada + nova emitida; registrado na trilha |
| 3. Suspender conta (Spec 16) | Sim | Suspensão com motivo na trilha |
| 4. Auditoria de transações | Sim | Nenhum saque indevido no período (ledger conferido) |
| **Gap encontrado** | — | **Sem dashboard por chave/principal** (só métrica agregada) — anotado como melhoria (métricas por principal) |

---

## Retenção e busca de logs (Spec 64 — critério 8)

### Janela de retenção

- **Railway (UI)**: logs da aplicação ficam disponíveis na UI por **~24h**
  (janela padrão do Railway). Para além disso, é preciso **log drain**.
- **PostgreSQL**: `platform_activity_log` (Spec 41) e `notification_outbox` são
  persistidos no banco — **não expiram** (retidos enquanto o banco existir;
  backup em `ops/backup-restore.md`). É a fonte de investigação de longo prazo.
- **Métricas**: `job_runs` (Spec 53) retido 30 dias (configurável
  `foxcash.scheduler.job-runs.retention-days`).

### Caminho de investigação (pós-incidente)

```
1. Alerta recebido (webhook/fallback log) → 2. Buscar o período no log da app
   (Railway UI/drain) por X-Request-Id → 3. Cruzar com platform_activity_log
   (SQL: SELECT * FROM platform_activity_log WHERE ... periodo/actor) →
   4. Conferir métricas (job_runs, foxcash_*, Tomcat/Hikari/JVM) → 5. Decidir ação.
```

- **`X-Request-Id`**: gerado por request (Spec 57 — `RequestIdFilter`), presente
  nos logs estruturados e na resposta (`X-Request-Id` header). É a chave para
  reconstruir um incidente (mesmo request em filtros/controller/DB/traces).
- **Exemplo de busca** (Railway UI): `X-Request-Id=abc123` ou
  `Webhook rejeitado: assinatura` no período do alerta.
- **SQL de trilha**: `SELECT actor_type, action, created_at, details FROM
  platform_activity_log WHERE created_at BETWEEN ... ORDER BY created_at;`
  (mascaramento: `details` é serializado com máscara — Spec 41).

### Log drain (opcional — recomendado)

- **Railway → Axiom/Loki (free tier)**: configurar log drain no Railway
  (Settings → Logging) apontando para o coletor (ex.: Axiom free com API token,
  ou Grafana Loki via OTLP). A partir daí a retenção deixa de ser 24h.
- O drain **não substitui** o `platform_activity_log` (trilha auditável no banco)
  — complementa para debugging/forense de longo prazo.
- **Segurança do drain**: credencial do drain (API token) é segredo — usar env
  var, nunca yml; o drain transporta logs que podem conter `X-Request-Id` e
  contextos de request (o `SensitiveDataLoggingFilter` — Spec 63 — já máscara
  CPF/CNPJ nos logs de request/response).
- Anotação no `deploy/railway.md`: adicionar seção "Log drain (opcional)" com o
  passo a passo do coletor escolhido.

---

## Referências

- `ops/backup-restore.md` — DR do PostgreSQL (perda de dados).
- `deploy/railway.md` — deploy, HA, env vars, healthcheck, backups do Railway.
- `management/specs/63-edge-waf-bot-mitigation/` — Cloudflare, WAF, edge rate
  limit, Turnstile (barreiras de borda usadas nas Classes 1-2).
- `management/specs/64-sec-observability-incident-response/` — esta spec
  (eventos de segurança, limiares, design).
- Specs de apoio: 35 (outbox/webhook), 41 (trilha), 53/54 (webhook de operador,
  OUTBOX_DEAD), 57 (tracing/`X-Request-Id`), 58 (rate limit por principal).
