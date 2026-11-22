# Roteiro de melhorias — TrevoPay (2026-08-10)

> Derivado da análise técnica (`docs/analise-tecnica-2026-08-10.md`).
> **Riscos → tarefas imediatas** (executar HOJE, sem código ou ação rápida).
> **Notas por subsistema → roteiro de melhorias** (evolução contínua).
> **Recomendações → Spec 66** (próxima spec).

---

## A. Riscos → Tarefas imediatas

### R1 — ✅ FECHADO (2026-08-10): Global API Key do Cloudflare exposta em chat
- **Roll confirmado pelo usuário**: a chave antiga (`<REDACTED_KEY>`) morreu
  (auth → `Unknown X-Auth-Key or X-Auth-Email`, verificado ao vivo).
- **Mitigação**: criado API Token escopado `foxcash-ops-scoped-2026-08-10`
  (id `<TOKEN_ID>`) — permissões mínimas na zona
  `foxcash.example.com` (DNS/WAF/rate limiting/SSL), sobrevive ao Roll; validado
  (lê a zona, NEGADO fora dela). Guardar em gestor de senhas.
- **Aprender**: credenciais nunca em chat; usar tokens escopados, não Global Key.

### R2 — ALTA: Rollback do edge incompleto (critério 9 da Spec 63) — ✅ FECHADO (Spec 66 Fase A, 2026-08-10)
- **Sintoma medido**: proxy OFF → DNS resolve para o Railway, mas HTTPS direto falha
  (`Connection was reset` no handshake) — o Railway não serve cert de `api.foxcash.example.com`
  no IP do domínio default.
- **Ação**: **Spec 66 Fase A** — registrar o custom domain do Railway como origem
  (CNAME para o endpoint de custom domain) + testar rollback real + atualizar
  `deploy/edge.md` §Rollback.
- **Estado (2026-08-10)**: **VALIDADO** — custom domain ACTIVE (id `<DOMAIN_ID>`),
  CNAME `api` → `<custom-endpoint>.up.railway.app` (endpoint de custom domain que o Railway
  instrui — o antigo `foxcash-production.up.railway.app` não serve o cert do domínio
  custom) + TXT `_railway-verify.api` criado (`Verified: yes`) → flip proxy OFF →
  Railway emitiu o cert (`CN=api.foxcash.example.com`, Let's Encrypt, `VALID`) → **HTTPS
  direto 200** (sem `CF-Ray`, health UP) → proxy ON restaurado (200 via Cloudflare,
  `CF-Ray` presente). Procedimento reproduzível em `deploy/edge.md` §Rollback.

### R3 — ✅ CONFIGURADO (2026-08-10): Operador webhook
- **Env vars setadas no Railway**: `NYX_NOTIFICATION_OPERATOR_WEBHOOK_URL`
  (webhook Discord) + `NYX_NOTIFICATION_OPERATOR_WEBHOOK_SECRET` (gerado).
- **Ajuste de código**: `HttpMerchantNotifier` ganhou formatador nativo para
  Discord (campo `content` — o Discord rejeita o envelope puro com 400) quando a
  URL é de webhook do Discord; demais destinos mantêm o envelope assinado.
  Testes 11/11; deploy `d2525272`.
- **Verificação**: alertas `BODY_INSPECTION_BLOCKED` publicados (2, um por
  réplica) e entregues ao operador sem erro de retry nos logs.
- **Confirmação pendente**: visual no canal do Discord.

### R4 — MÉDIA: Alertas de segurança por-instância — ✅ ENDEREÇADO (Spec 66 Fase B)
- **Sintoma medido**: com 2 réplicas, o limiar 5/60s do `BODY_INSPECTION_BLOCKED` pode
  não disparar (contadores locais dividem o volume).
- **Ação**: **Spec 66 Fase B** — contadores de rajada agregados via Redis (fail-open).
- **Estado (2026-08-10)**: porta `AlertCounterStore` + `InMemoryAlertCounterStore`
  (default) + `RedisAlertCounterStore` (script Lua `INCR`+`EXPIRE`, `DEL` no disparo,
  fail-open com WARN throttled) + `SecurityAlertProperties.store`
  (`NYX_SECURITY_ALERT_STORE`, mesmo padrão do `RateLimitStore`); `SecurityAlertPublisher`
  usa a porta. **`implemented`** (commit `caa8b7d`); `mvn test` **1169/0/0**.
  **Pendente pós-deploy**: ativar `NYX_SECURITY_ALERT_STORE=redis` no Railway e smoke
  de agregação multi-instância (2 réplicas → 1 alerta) — item 10 da task 66.
  **✅ CONCLUÍDO (2026-08-16, verificado 2026-08-20)**: `FOXCASH_SECURITY_ALERT_STORE=redis`
  ativo no Railway; smoke 2 réplicas → 1 alerta `BODY_INSPECTION_BLOCKED` (limiar
  5/60s agregado via Redis, script Lua atômico, 0 WARN fail-open) — evidência em
  `management/specs/66-resiliencia-operacional/task.md` (deploy `94861317`).

### R5 — MÉDIA: Calibração do body inspection — ✅ FECHADO (2026-08-20)
- **Ação**: rodar `ops/monitor-body-inspection.sh` (cron */15; exit 2 = houve bloqueio);
  se falso positivo legítimo → `NYX_BODY_INSPECTION_ENABLED=false` + afinar padrão
  (deploy/edge.md §7.5). **Spec 66 Fase C** (métrica + runbook de calibração).
- **Estado (2026-08-10)**: **Fase C `implemented`** — métrica `nyx.body_inspection.blocks`
  (Counter com tags `pattern`/`category`) no `RequestBodyInspectionFilter` + runbook de
  calibração em `deploy/edge.md` §7.6 + cron `*/15` documentado no header do script.
  **Ação do operador pendente**: agendar o script no cron (crontab/agendador do SO).
- **Fechamento (2026-08-20)**: cron agendado no Agendador de Tarefas do Windows —
  tarefa `FoxCash Body Inspection Monitor` (a cada 15 min) via
  `ops/cron-body-inspection.cmd` → `ops/cron-body-inspection.sh` →
  `ops/monitor-body-inspection.sh --json --minutes 15`; validado com
  `schtasks /run` (Último resultado: 0, log `logs/body-inspection-monitor.log`
  com exit=0). Corrigidos no caminho: monitor com `RAILWAY_PROJECT` explícito
  (roda sem `railway link`), TMPDIR robusto no Windows (sem `mktemp`; fallback
  `cygpath`/`<repo>/.tmp`), alerta sem `jq`. Comando de recriação em
  `deploy/edge.md` §7.6.

### R6 — MÉDIA: Specs 63/64/65 sem `/spec-audit` — ✅ FECHADO (2026-08-10)
- **Ação**: agendar auditoria das 3; pendências conhecidas: critério 6 (webhook PSP
  real com assinatura), critério 7 (evidência de log do CF-Connecting-IP), critério 2
  (soak completo do flood) — da Spec 63.
- **Estado (2026-08-10)**: `/spec-audit` **concluído nas 3** — Spec 63 **10/10**
  (commit `453ea45`; 2 deferimentos documentados: webhook real fecha na Fase 2 Pagar.me,
  rollback na Spec 66 Fase A), Spec 64 **9/9** (`60d4408`), Spec 65 **7/7** (`e221266`).
  As 3 marcadas `audited` no roadmap.

### R7 — BAIXA: Capacidade (gargalo Postgres)
- **Ação**: roteiro (seção B-Infra): pool Hikari por réplica (60×8<500) para scale-out
  até 8 réplicas; PgBouncer só se scale-out >8 (ADR-013).

---

## B. Notas por subsistema → Roteiro de melhorias

### B.1 Autenticação & Autorização
- [ ] Autenticação adaptativa/step-up (proposta futura registrada no roadmap Fase 17) —
      rever quando houver evidência de necessidade.
- [ ] MFA para o lojista (portal) — hoje só o master tem TOTP; avaliar demanda.
- [ ] Revisar exposição de `expose-reset-token` em perfis não-prod.

### B.2 Segurança & Segredos
- [ ] **Gestão de segredos profissional (Doppler/Vault)** — proposta futura documentada
      (roadmap Fase 17); avaliar quando houver >1 operador/ambiente.
- [ ] Rotação periódica de chaves (API key do lojista, webhooks, admin) — runbook.
- [ ] Considerar rate limit do `forgot-password` por e-mail (já por IP — Spec 42 B-02).

### B.3 Rate limiting & Edge
- [ ] Regras de edge rate limit por rota (teto diferenciado) — exige plano Pro;
      só com evidência de ataque (regra de ouro).
- [ ] WAF com inspeção de body (WAF Advanced) — só com evidência (app já cobre via
      RequestBodyInspectionFilter).
- [ ] Revisar ranges de IP do Cloudflare no TrustedProxies (deploy/edge.md).

### B.4 Observabilidade
- [ ] **Alerting de métricas (Grafana Cloud)** — Fase C da Spec 64 (opcional documentada):
      scrape autenticado + regras 5xx/readiness/Hikari/heap/outbox.
- [ ] Dashboard Prometheus dos 403s do body inspection (Spec 66 Fase C — métrica).
- [x] Smoke multi-instância do rate limit Redis — **FECHADO (2026-08-20)**: 25 GETs
      rápidos no `/api/v1/foxcash/storefront` → 20×401 + **429 na 21ª** (teto 20/60s
      por IP agregado via Redis entre 2 réplicas; por-instância daria ~40ª). IP
      liberado após a janela (60s).

### B.5 Notificações / Outbox
- [ ] DLQ física se `OUTBOX_DEAD` ficar frequente (hoje: alerta sem DLQ — decisão de
      simplicidade documentada).
- [ ] Configurar operador webhook (R3) e validar entrega ponta-a-ponta.

### B.6 Capacidade & Escala
- [ ] Pool Hikari por réplica (60) para scale-out até 8 réplicas — evidência ADR-013/F9.
- [ ] PgBouncer (transaction pooling) apenas se scale-out >8 for necessário.
- [ ] Re-benchmark após qualquer mudança de auth/outbox (evidência antes/depois).

### B.7 White-label / Bots
- [ ] Modelagem 1:N de stores (hoje 1:1 — UNIQUE; remover sem redesenho).
- [ ] Ação `ADD_TO_GROUP` no Discord (hoje `unsupported` — precisa suporte da API do
      Discord p/ grupos).
- [ ] Auditar payloads de ação (StoreButtonPayloadCodec/PlanActionPayloadCodec) ao
      adicionar novos tipos.

### B.8 Portal frontend
- [ ] Cobertura E2E UI para as páginas novas (destinations/webhooks/credentials já
      cobertas em portal.spec; revisar após mudanças).
- [ ] Acessibilidade/UX (sem pendência crítica identificada).

### B.9 Persistência
- [ ] Revisar índices após crescimento (pg_stat_statements é a fonte — Spec 62).
- [ ] Retenção/purga de tabelas grandes (job_runs já tem cleanup; avaliar
      platform_activity_log/notification_outbox).

### B.10 Testes
- [ ] Manter o global-setup do E2E sincronizado ao adicionar tabelas (L6).
- [ ] Ampliar Pitest p/ novos serviços (meta 95% killed).
- [ ] Testes de contrato dos novos adapters de payout (herdar PayoutProviderContractTest).

---

## C. Recomendações → Spec 66

**Spec 66 — Resiliência operacional pós-análise** (`implemented` no roadmap — Fases B/C; Fase A bloqueada):

| Fase | Entrega | Fecha | Estado |
|---|---|---|---|
| **A — Rollback de edge completo** | Custom domain do Railway como origem do Cloudflare; teste de rollback real; runbook atualizado | Critério 9 da Spec 63 (R2) | ✅ **VALIDADA (2026-08-10)** — HTTPS direto 200 sem `CF-Ray`; proxy ON restaurado |
| **B — Alertas de segurança agregados** | Contadores de rajada em Redis (script Lua, fail-open → fallback local); limiar global entre réplicas | R4 | ✅ **implemented** (`caa8b7d`); smoke multi-instância pendente |
| **C — Monitoramento proativo do body inspection** | Métrica Micrometer `nyx.body_inspection.blocks` (contador por padrão/categoria); runbook de calibração; cron do monitor | R5 | ✅ **FECHADO (2026-08-20)** — métrica + runbook §7.6 + cron agendado no Windows (`FoxCash Body Inspection Monitor`, */15, validado) |

Escopo total em `management/specs/66-resiliencia-operacional/`.

---

## D. Ações de hoje (checklist do operador)

- [x] **R1**: rotacionar a Global API Key do Cloudflare — **FECHADO** (roll pelo usuário; token escopado `nyxcash-ops-scoped-2026-08-10` criado e validado)
- [x] **R3**: setar operador webhook no Railway (URL + secret) — **CONFIGURADO** (deploy `d2525272`; entrega verificada no log)
- [x] Turnstile: testar login real via UI do portal (widget → 200) — verificado ao vivo (edge item 5)
- [x] R5: agendar `ops/monitor-body-inspection.sh` no cron (*/15) — **FECHADO (2026-08-20)** — tarefa `FoxCash Body Inspection Monitor` no Agendador do Windows (validada: Último resultado 0)
- [x] R6: agendar `/spec-audit` das specs 63/64/65 — **FECHADO** (63 `453ea45` 10/10, 64 `60d4408` 9/9, 65 `e221266` 7/7)
- [x] **Spec 66 Fase A**: flip DNS/proxy do rollback + teste real — **FECHADO (2026-08-10)** — HTTPS direto 200 validado (sem `CF-Ray`), proxy ON restaurado; procedimento em `deploy/edge.md` §Rollback
- [x] **Spec 66 item 10**: ativar `NYX_SECURITY_ALERT_STORE=redis` no Railway + smoke multi-instância (2 réplicas → 1 alerta) — **FECHADO (2026-08-16)** — smoke ao vivo deploy `94861317`; variável confirmada ativa em 2026-08-20
