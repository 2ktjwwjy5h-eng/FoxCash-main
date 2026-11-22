# Análise técnica completa — TrevoPay (2026-08-10)

> Relatório consolidado da sessão de análise profunda (todos os subsistemas),
> riscos transformados em tarefas e recomendações para a Spec 66.
> Gerado por análise de código + documentos SDD (roadmap/arch/adr/lessons/specs).

---

## 1. Visão geral

**TrevoPay** = plataforma white-label de pagamentos Pix para vendedores que entregam
produtos digitais via bots (Discord/Telegram). Monetização: **R$ 0,50/transação
aprovada + R$ 69,95/mês**.

| Camada | Tecnologia | Tamanho |
|---|---|---|
| Backend | Java 21, Spring Boot 3.3, Maven, hexagonal | 643 arquivos, ~36.4k LOC |
| Portal | React 18 + TypeScript + Vite (SPA servida pelo Spring) | 11 páginas + 2 componentes |
| Bots | Node.js + TS (core + discord + telegram) | 12 módulos core, 7 testes |
| Testes | JUnit5/AssertJ/Mockito + Pitest; Playwright E2E; k6 | 151 arquivos / 1153 testes; 10 specs E2E |
| Infra | Railway (app + PG + Redis), Cloudflare edge, Flyway (45 migrations) | `deploy/`, `ops/` |

**Dependências**: Spring Web/Data-JPA/Security/Validation/Redis/Actuator, Flyway,
ShedLock, Micrometer (Prometheus + OTel), Pagar.me/Asaas (PSP).

---

## 2. Arquitetura (ADR-001)

```
infrastructure (adapters) → application (use cases) → domain (núcleo puro)
```

- `domain/`: 20+ agregados, 7 serviços puros (PricingCalculator, InvoiceCalculator,
  Totp, PasswordPolicy, DocumentValidator, EmailValidator, WithdrawalDestinationValidator)
- `domain/port/out`: 44 ports (repositórios + PixProviderClient + PayoutProviderClient
  + MerchantNotifier + RateLimitStore + SessionTokenGenerator)
- `application/service/`: 66 use cases
- `infrastructure/`: 42 controllers REST + 2 webhook controllers + JPA + notifier + security

---

## 3. Superfície da API (42 controllers + 2 webhooks)

**Auth/conta (público)**: `POST /api/v1/merchants` (cadastro, Turnstile+body),
`GET /merchants/{id}`, `POST /merchant-auth/login|logout|forgot-password|reset-password`,
`POST /auth/master/login|logout`, `POST /master/mfa/activate|confirm|deactivate|recovery-codes`,
`GET /portal/turnstile-config`, SPA paths.

**Master/Admin**: `/api/v1/admin/merchants|invoices|summary`; `/api/v1/master/*`
(transactions, earnings/fees, report/activity, audit, revenue/summary|ledger, risk,
suspend|release, chargebacks, withdrawals/administrative|block|release, receivables,
collect, write-off, reconciliation/run|issues, profiles/{id}/activate|deactivate).

**Portal lojista**: `/api/v1/portal/*` (me, dashboard 4 endpoints, wallet 5 endpoints,
charges/gift-presets, sales, credentials/revoke, destinations CRUD, webhooks CRUD+test,
plans CRUD+actions, store/buttons, store/messages).

**API transacional**: `/api/v1/nyx/pix/cob`, `/nyx/plans/{id}/purchase`,
`/nyx/storefront`, `/nyx/deliveries/*` (pending, by-transaction, access, retry,
actions/{seq}/complete), `/nyx/scheduled-messages/{id}/sent`, `/transactions/*`,
`/merchants/{id}/withdrawals`, `/billing/preview`.

**Webhooks inbound**: `POST /webhooks/pix` e `POST /webhooks/payouts` (HMAC).

---

## 4. Domínio financeiro

- **Cobrança** (`CreatePixChargeService`): PENDING persistida ANTES do PSP em tx
  própria (REQUIRES_NEW) com idempotencyKey única/lojista (Spec 28); PSP **fora da
  tx**; timeout → 502 `psp_timeout` (PENDING recuperável); erro → FAILED; corrida →
  409 via `uq_pix_transactions_idempotency`; `attachAndPublish` com lock
  `findByIdForUpdate` (serializa retries — achado F4 62: 26 eventos duplicados → 1×);
  teto de validade 86.400s.
- **Wallet/Ledger** (Specs 27/30): ledger **append-only**, entry única por
  (tipo,origem) via `uq_wallet_ledger_ref`; `balance_after` pós-mutação; tipos
  OPENING_BALANCE/SALE_CREDIT/REFUND_DEBIT/CHARGEBACK_DEBIT/WITHDRAWAL_DEBIT/
  INVOICE_FEE_DEBIT/RECEIVABLE_DEBIT/WITHDRAWAL_REVERSAL; cobrança cumulativa p/
  parciais; reconciliação diária 04:00 (`saldo = Σ ledger`).
- **Webhook de pagamento** (`ProcessPixWebhookService`): lock por externalReference;
  replay no-op; crédito SALE_CREDIT + receivables + delivery + outbox SALE_PAID na
  MESMA transação; amount divergente → no-op + issue (B-05).
- **Saques** (Specs 14/32/36/38): PROCESSING (débito imediato) → COMPLETED | FAILED
  (estorno automático WITHDRAWAL_REVERSAL); `PayoutProviderClient` Pagar.me real +
  mock; reconciliação de órfãos 04:30 (pending-since-hours 2); bloqueio/release
  (não estorna efetivados); saque administrativo "lacrado" (motivo obrigatório,
  nunca negativo, recolhe de bloqueado).
- **Faturamento** (Specs 6/33/34): fatura dia 1 (00:05), cobrança no vencimento
  (02:30, total/parcial), receivables OVERDUE, write-off, master_ledger
  (TRANSACTION_FEE + MONTHLY_FEE na quitação).
- **Antifraude** (Spec 19): única regra de bloqueio automático = buyerId (hash
  SHA-256) com chargeback na janela → `fraud_buyer_blocked`; velocidade nunca
  bloqueia; risk score 0-100 + auto-suspensão configurável.

---

## 5. Segurança (defesa em profundidade)

**Cadeia de filtros** (ordem): `RequestIdFilter → RequestBodyInspectionFilter (403
SQLi/XSS/traversal) → RateLimitFilter (429 por IP+rota) → MasterAuth →
MerchantPortalAuth → ApiKeyAuth → AuthenticatedRateLimitFilter` + servlet:
`RequestBodySizeFilter (413 >1MiB)` e `WebhookSignatureFilter (HMAC + RL webhook 120/min)`.

- **Auth**: API key SHA-256 (+admin), master BCrypt + MFA TOTP (RFC 6238, secret
  160 bits criptografado, recovery codes single-use, lockout TOTP 5), portal sessão
  opaca, lockout login 5/15min, password reset token `rst_` one-time (anti-enumeração
  em prod), BCrypt dummy anti-timing (B-01).
- **Autorização**: roles + ownership (IDOR) + escopo de API key; sessão do portal
  NÃO acessa a API REST.
- **Cripto**: AES-256/GCM em repouso (CryptoConverter, formato `v1:base64(iv+ciphertext+tag)`,
  ThreadLocal<Cipher>), hash determinístico p/ documento.
- **Rate limit**: Redis (Lua atômico, fail-open ADR-010) + memory; tetos 20/120/300/120.
- **Edge**: Cloudflare WAF/edge RL/DDoS + Turnstile (fail-open) + body inspection na app.
- **Auditoria**: platform_activity_log (append-only) + master_audit_log (before/after
  mascarado, reason obrigatório) + SecurityAlertPublisher (Spec 64).
- **Erros**: ErrorResponse(code,message) uniforme; GlobalExceptionHandler mapeia ~45
  exceções → HTTP; filtros escrevem JSON direto.

---

## 6. Notificações / Outbox / Jobs

- Outbox na mesma transação; entrega at-least-once com eventId; 1 evento/tx própria
  (NotificationProcessor); OUTBOX_DEAD sem DLQ física; operador via HMAC com fallback log.
- 6 jobs ShedLock (pool 4, graceful): fatura 00:05 dia 1, cobrança 02:30, risco 03:00,
  reconciliação 04:00, saques 04:30, cleanup 04:45 + poll outbox 10s (loop de lotes).
- JobExecutionService: métrica nyx.job.* + trilha job_runs + alerta JOB_FAILED.

---

## 7. White-label, planos, entregas, bots

- Store 1:1 merchant; storefront público (planos ativos sem payload de ações em claro,
  botões em ordem, mensagens por etapa); PlanPurchase herda idempotência + antifraude;
  deliveries com ações PENDING→DONE/FAILED (unique por delivery+seq), retry, evidência.
- Bots stateless (sem banco): polling 15s, HMAC timing-safe, dedup eventId (TTL 10min),
  backoff 429, cache storefront 30s; Discord (grant_role/send_*; add_to_group →
  unsupported) e Telegram; 1 plataforma por loja.

---

## 8. Portal frontend

SPA React/Vite servida pelo Spring; 11 páginas; token sessionStorage; apiClient com
redirect 401 (PUBLIC_PATHS); Turnstile no login (site key via /portal/turnstile-config);
CSP libera challenges.cloudflare.com.

---

## 9. Observabilidade

- Actuator: health (probes liveness/readiness; Redis e Mail health OFF — infra opcional),
  info, prometheus, metrics; base /actuator (admin-only exceto health).
- Métricas: nyx.job.duration/success/failure + HTTP/Hikari/JVM.
- Tracing: Micrometer + OTel bridge → OTLP (sampling 0.1; fail-open sem collector).
- Logs estruturados ts= level= logger= requestId= traceId= spanId= msg=;
  SensitiveDataLoggingFilter sanitiza CPF/CNPJ.

---

## 10. Persistência (35 entidades JPA, 45 migrations)

- 28 tabelas: merchants, pix_transactions, invoices, wallet_ledger, withdrawals,
  merchant_withdrawal_destinations, receivables, master_ledger, chargebacks,
  merchant_risk, masters, master_sessions, master_mfa_methods/totp/recovery_codes,
  merchant_credentials/sessions/api_keys, notification_outbox, platform_activity_log,
  master_audit_log, password_reset_tokens, reconciliation_issues, stores, plans,
  plan_deliveries(+actions+exec+access_logs), store_buttons, store_messages,
  store_scheduled_messages, gift_presets, merchant_webhooks, job_runs, shedlock.
- Unicidades parciais (`WHERE col IS NOT NULL`): idempotency_key, external_reference,
  document_hash, email(lower), open per merchant; uq_wallet_ledger_ref; uq por
  delivery+seq; uq reconciliation_open; uq chargeback por transação.
- Append-only: wallet_ledger, platform_activity_log, master_audit_log, job_runs.
- Índices alinhados a hot paths medidos (V44 merchant_status, V45 outbox pending).

---

## 11. Infra / Deploy / Ops

- Dockerfile multi-stage (Maven→JRE), JAVA_OPTS MaxRAMPercentage=75; railway.json
  (2 réplicas, healthcheck readiness, restart ON_FAILURE).
- Profiles: base seguro-por-default → dev (fakes) → prod (HTTPS/fail-fast) →
  railway (edge TLS, $PORT, forward-headers-strategy=framework, SSL off).
- Cloudflare edge aplicado (SSL strict, WAF 4 regras, edge RL 50/10s consolidada,
  Bot Fight ON, Turnstile ativo, body inspection ON).
- Ops: backup.sh (pg_dump -Fc), restore.sh, monitor-body-inspection.sh (novo),
  security-incident.md (5 classes).

---

## 12. Testes

- 1153 unitários (64 application / 58 infra / 29 domain) + Pitest 93-100% killed
  por pacote; 10 specs E2E Playwright (auth, journey, portal, portal-ui, psp, outbox,
  bots, security, concurrency, reliability); bots vitest; k6 com gates.
- Padrões: mocks de ports (sem Spring), ArgumentCaptor p/ estado persistido,
  never() p/ idempotência, assertThatThrownBy p/ contratos, vetores RFC p/ TOTP,
  servlet mocks p/ filtros.

---

## 13. Capacidade (Spec 62 — medido, não estimado)

- ~1.246 vendas/s (4 réplicas), ~1.554/s (8); gargalo central = Postgres
  max_connections=500; 16 réplicas degrada; meta 5k/s não atingida (reportada);
  PgBouncer/réplicas dispensados por evidência (ADR-013) com recomendação revisada
  (pool 60/réplica p/ scale-out até 8); zona sagrada 0/0/0/0; outbox ~135-140/s.

---

## 14. Processo SDD/ADR

- Ciclo: /spec-plan → /spec-new → /spec-verify (idempotente) → /spec-implement →
  /spec-audit (idempotente, final). `audited` = DONE+LIVE.
- Artefatos: roadmap (índice+changelog), vision (V-blocks), adr (15), arch, lessons (L1-L15).
- Regras de ouro: o reportado é o medido; não pagar por capacidade sem evidência;
  zona sagrada no caminho consistente; disponibilidade primeiro (fail-open);
  auditoria = unit + E2E + smoke real (L7).
- Estado: 67 specs → 61 audited, 3 implemented (63/64/65), 3 discarded.

---

## 15. Configuração completa

Perfis e env vars em detalhe: ver o relatório da sessão + `application*.yml`.
Destaques: NYX_CRYPTO_SECRET/NYX_ADMIN_API_KEY/NYX_PSP_* fail-fast; Turnstile e body
inspection via env; rate limit store memory|redis; NYX_TRUST_PROXY=true no Railway;
operador webhook pendente; OTLP fail-open.

---

## 16. Fluxo ponta-a-ponta (venda) e fluxo do master

Ver `docs/roteiro-melhorias-2026-08-10.md` §fluxos (diagramas completos) e a seção
"Trace" desta sessão: compra no bot → cobrança (idempotência) → pagamento (lock +
ledger + outbox) → entrega (HMAC + dedup + ações) → mensagens/jobs; e master:
login (BCrypt dummy + lockout + MFA) → gestão (guard can_manage_masters) → saque
administrativo (lacrado, Fase A/B) → auditoria (before/after mascarado).

---

## 17. Riscos identificados → tarefas imediatas

| # | Risco | Prioridade | Tarefa imediata |
|---|---|---|---|
| R1 | Global API Key do Cloudflare exposta em chat | **CRÍTICA** | **Rotacionar** a chave (My Profile → API Keys → Roll) — ver roteiro |
| R2 | Rollback do edge incompleto (proxy OFF não restaura HTTPS) | ALTA | Spec 66 Fase A (custom domain do Railway como origem + teste) |
| R3 | Operador webhook não configurado (alertas só em fallback log) | ALTA | Setar `NYX_NOTIFICATION_OPERATOR_WEBHOOK_URL`+`_SECRET` no Railway |
| R4 | Alertas de segurança por-instância (2 réplicas podem não disparar limiar) | MÉDIA | Spec 66 Fase B (contadores agregados via Redis) |
| R5 | Calibração do body inspection em andamento | MÉDIA | Rodar `ops/monitor-body-inspection.sh` periodicamente (cron) |
| R6 | Specs 63/64/65 sem `/spec-audit` | MÉDIA | Agendar auditoria (critérios 6/7/2 da 63 pendentes de evidência) |
| R7 | Capacidade: meta 5k/s não atingida (gargalo Postgres) | BAIXA | Roteiro: pool Hikari por réplica + PgBouncer se scale-out >8 |

**Ações imediatas (sem código)** — ver `docs/roteiro-melhorias-2026-08-10.md`:
1. Rotacionar a Global API Key do Cloudflare (R1 — HOJE).
2. Configurar o operador webhook no Railway (R3).
3. Testar login real via UI do portal (Turnstile).
4. Agendar o monitor de body inspection no cron.

---

## 18. Recomendações → Spec 66

A análise técnica recomenda a **Spec 66 — Resiliência operacional pós-análise**:
- **Fase A**: rollback de edge completo (fecha critério 9 da Spec 63).
- **Fase B**: alertas de segurança agregados entre réplicas (Redis).
- **Fase C**: métrica de bloqueios do body inspection + runbook de calibração.

Detalhes completos em `management/specs/66-resiliencia-operacional/`.
