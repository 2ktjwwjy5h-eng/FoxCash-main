---
name: Trace de transação — rastreio ponta a ponta (bot → gateway → PSP → ledger → webhook)
status: audited
references:
  - "management/vision.md#V92"
  - "management/specs/35-outbound-webhook/requirements.md"
  - "management/specs/41-atividade-plataforma/requirements.md"
  - "management/specs/27-wallet-ledger/requirements.md"
  - "management/specs/34-master-ledger/requirements.md"
  - "management/specs/75-leads-metricas-bots/requirements.md"
  - "management/specs/18-security-hardening/requirements.md"
  - "management/specs/65-otimizacao-charge-outbox/requirements.md"
---

# Spec 125 — Trace de transação: rastreio ponta a ponta

## O que entrega

Uma tela/endpoint **read-only** para o suporte: dado o txid (ou a
`idempotencyKey`, ou o UUID interno), o operador vê a **jornada completa de uma
transação** — Bot → Gateway → PSP → Ledger → Webhook — em uma linha do tempo
única, sem juntar tabelas manualmente.

O fio condutor **já existe**: `PixTransaction.id` é a chave presente em
`platform_activity_log` (`entityId`), `wallet_ledger`/`master_ledger`
(`referenceId`), no payload do outbox (`transactionId`), na entrega (Spec 49) e
no funil do bot (Spec 75, `transactionId`). Esta spec **agrega** essas fontes e
remove o "junta 5 tabelas na mão" do processo de suporte.

**Fases:**

| Fase | Entrega | Esforço |
|---|---|---|
| 1 — Trace funcional | Endpoint que monta a timeline (activity + ledger + transação + funil) | Médio |
| 2 — Entrega no trace | Outbox com referência (entrega do webhook: `attempts`/`lastError`) | Médio |
| 3 — traceId de negócio | `trace_id` propagado (MDC nos logs do worker) | Pequeno-médio (incremental) |
| 4 — UI no console | Tela "colar txid → jornada" | Médio |

O núcleo da spec é a **fase 1**; as fases 2–4 são tarefas incrementais (o
endpoint já funciona sem elas — ver design D8).

## Critérios de aceitação

**C1 — Lookup flexível.** `GET /api/v1/master/trace/transactions/{ref}` resolve
`ref` como: UUID interno (`pix_transactions.id`) **ou** `externalReference`
(txid/code do PSP) **ou** `idempotencyKey` (com `merchantId`). Transação
inexistente → `404 TransactionNotFound` (sem revelar existência).

**C2 — Timeline fiel.** A resposta monta a jornada a partir do que REALMENTE
ocorreu (fontes agregadas por `transaction.id`), ordenada por timestamp, com
`stage` (`BOT`/`GATEWAY`/`PSP`/`LEDGER`/`WEBHOOK`). Nenhuma etapa é inventada:
sem bot → sem `BOT`; sem webhook de saída → sem `WEBHOOK`.

**C3 — Segurança e privacidade.** Endpoint exclusivo `ROLE_MASTER` (rota sob
`/api/v1/master/**` → `hasRole("MASTER")` no `SecurityConfig`, padrão dos
endpoints master de leitura — sem `MasterPermission` nova). Dados sensíveis
**mascarados** (reusa a máscara do `PlatformActivityRecorder` — o `details` da
activity já chega mascarado). Nunca vaza dados de outro lojista.

**C4 — Activity consultável por entidade.** `PlatformActivityQuery` ganha
`entityType`/`entityId` + **índice `(entity_type, entity_id, created_at)`**
(migration) — sem full-scan da `platform_activity_log`.

**C5 — Entrega do webhook no trace (fase 2).** `notification_outbox` ganha
coluna de referência (`entity_type`/`entity_id` + backfill do payload
existente); a timeline mostra `SALE_PAID_DELIVERED` com `attempts`/`lastError`/
`nextAttemptAt` (Spec 35 — retry/backoff).

**C6 — Testes.** Cobertura do lookup (3 formas), timeline completa (fluxo feliz
com bot + plano), timeline parcial (sem bot), 404, RBAC 403 sem ROLE_MASTER,
máscara (chave Pix nunca em claro), fase 2 (outbox com `attempts`). `mvn test`
verde.

## Dependências

- **Spec 35** (outbox — payload/envelope `WebhookEnvelope`, retry/backoff).
- **Spec 41** (trilha `platform_activity_log` + `PlatformActivityRecorder` com
  máscara).
- **Specs 27/34** (ledger wallet/master — `referenceType`+`referenceId`).
- **Spec 75** (funil `bot_leads`/`bot_funnel_events` por `transactionId`).
- **Spec 18** (`RequestIdFilter` — `X-Request-Id`/MDC já existem).
- **Spec 65** (drenagem do outbox — `NotificationDispatcherService`).

## Fora de escopo (fases futuras, não bloqueiam o núcleo)

- traceId de negócio **gerado e propagado** no MDC dos logs (fase 3) — o
  endpoint da fase 1 já responde "onde parou?" com `transaction.id`.
- Tela no console (fase 4).
- Acesso do **próprio lojista** ao trace da sua transação (decisão posterior —
  candidato a autoatendimento de suporte).
