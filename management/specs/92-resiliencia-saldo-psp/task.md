---
name: Resiliência de saldo no PSP (sweep + credencial isolada)
status: audited
---

# Spec 92 — Task: Resiliência de saldo no PSP (sweep + credencial isolada)

## F0 — Porta de saldo (critério 1)

- [x] T0.1: `PspBalanceProvider` + `PspBalance` (D2); mock (valor configurável).
- [x] T0.2: `PagarMeBalanceProvider` (GET /balance) + `AsaasBalanceProvider`
      (GET /v3/finance/balance) com `@ConditionalOnProperty`.
- [x] T0.3: Testes — `PspBalanceContractTest` (harness) + Mock/PagarMe/Asaas
      (MockWebServer: mapeamento + auth + timeout → `PspBalanceException`).
      Achado: `@JsonProperty("waiting_funds")` faltava no `BalanceResponse`.

## F1 — Job de sweep (critérios 2–3, 6)

- [x] T1.1: `PspSweepScheduler` (ShedLock `foxcash-psp-sweep` + `JobExecutionService`
      Spec 53; cron/kill-switch) — excedente (available − buffer) → `createPayout`
      com `withdrawalId` **UUID determinístico por dia** (`UUID.nameUUIDFromBytes("sweep:"+LocalDate)`
      — code do PSP estável; achado da verificação) para `sweep.destination-key`;
      skip se ≤ 0; fora do domínio de saque (sem linha em withdrawals).
- [x] T1.2: Métricas `nyx_psp_balance_*`/`nyx_psp_sweep_*` + alertas
      `SWEEP_EXPOSURE_HIGH`/`SWEEP_FAILED` (outbox Spec 35 + webhook Spec 64,
      `REQUIRES_NEW`, fail-safe) — `SweepAlertPublisher`.
- [x] T1.3: Testes — transfere excedente/skip/fail-fast/alertas
      (`PspSweepServiceTest`); kill-switch via `@ConditionalOnProperty` no scheduler.

## F2 — Credencial isolada (critério 4)

- [x] T2.1: `PspProperties.validate()` — fail-fast (qualquer perfil) se payout-secret ==
      charge-secret (Pagar.me e Asaas) via `requireDistinctSecrets`; mensagem clara.
- [x] T2.2: Runbook `deploy/railway.md` §2c — IP whitelist da credencial payout-scoped
      (egresso do Railway) + setup da conta blindada (chave EVP do destino) + envs.
- [x] T2.3: Teste — `PspPropertiesTest`: chaves iguais falham (pagarme/asaas),
      distintas passam, placeholder não dispara a isolação.

## F3 — Payout fallback (critério 5)

- [x] T3.1: `PayoutProviderRouter` (prioridade `payout-routing.providers`, bean
      `@Primary`; timeout/5xx → tenta B; erro definitivo propaga; sem poll —
      reconciliação Spec 36 resolve órfão). **Roteamento pós-fallback**: o
      provedor aceito é gravado em `withdrawals.payout_provider` (coluna V28
      existente); reconciliação/webhook `/webhooks/payouts` roteiam por ela.
      Adapters agora são beans SEMPRE (sem `@ConditionalOnProperty` exclusivo) —
      a seleção virou decisão do router; mock falha com `PayoutProviderException`.
- [x] T3.2: Testes — A 5xx → B; A erro definitivo → propaga; todos falham →
      última exceção; `getPayout` multi-get; fail-fast de config
      (`PayoutProviderRouterTest`).

## F4 — Config + fechamento (critérios 7–8)

- [x] T4.1: `SweepProperties` (blocos `sweep.*`/`payout-routing.*`) + binding
      contra o yml real (`SweepPropertiesBindingTest`, Spec 68/L16); fail-fast
      (enabled sem destination-key, buffer negativo) no `PspSweepService`.
- [x] T4.2: E2E `psp-sweep.spec.js` (job roda + SUCCESS em `job_runs`; replay
      no mesmo dia idempotente) — requer app com `NYX_PSP_SWEEP_ENABLED=true`,
      mock-balance alto e destination-key; cron curto no perfil dev.
- [x] T4.3: `mvn test` verde (1681/0/0) + E2E completo sem regressão
      (159 testes; psp-sweep 2/2 — poll por contagem, achado de precisão do
      started_at; master-console-ui com locator específico). `/spec-audit 92`
      pendente (etapa de fechamento final).

## §Implementação — decisões e desvios (registro)

- **Adapters de saída sem `@ConditionalOnProperty` exclusivo (Spec 92):** o
  `MockPayoutProviderClient`/`PagarMePayoutProviderClient` deixaram de ser
  condicionais — todos são beans e o `PayoutProviderRouter` (`@Primary`)
  decide a ordem (prioridade `payout-routing.providers` ou só
  `payout-provider`). O fail-fast de credencial continua no
  `PspProperties.validate()`.
- **Mock falha com `PayoutProviderException`:** a falha simulada do mock
  (`mock-payout-failure`) passou a lançar `PayoutProviderException` (contrato
  da porta, Spec 36) — o router a captura para fallback; teste atualizado.
- **Isolação de credenciais em qualquer perfil:** `requireDistinctSecrets` roda
  no `validate()` sempre (não só em prod) — chave de cobrança == chave de
  saque/sweep do mesmo adquirente quebra o boot.
- **`@JsonProperty("waiting_funds")` no `PagarMeBalanceProvider`: achado do
  teste de mapeamento (T0.3) — sem ele o `blocked` vinha 0 (snake_case do
  Pagar.me v5 não liga no record).
- **Dev profile:** cron do sweep curto (`*/30 * * * * *`) no perfil dev para o
  E2E (mesmo padrão do auto-withdraw, Spec 70 D10.1); kill-switch segue OFF
  por default (`enabled=false`).
- **E2E — poll por contagem em vez de `started_at > max(...)` (achado de
  precisão do T4.2/T4.3):** o `job_runs.started_at` é gravado com precisão de
  microssegundos (`Instant.now()` do Java) e o JS Date do node-postgres trunca
  para ms — o `max(started_at)` truncado casava com a própria linha do max e o
  poll quebrava na 1ª iteração (`after == before`, falso negativo). O poll
  agora aguarda `count(*)` AUMENTAR (inteiro, sem sensibilidade a precisão de
  timestamp). Mesma família de bug no `master-console-ui.spec.js`:
  `text=Saldo do wallet` casava 2 elementos (heading do card da tab earnings +
  item da lista de métricas do detalhe) → strict mode violation; locator
  trocado por `getByRole('heading', { name: 'Saldo do wallet' })`.

## Verificação (`/spec-verify 92` — 2026-08-14)

Critérios checados contra o código real; **2 achados de precisão corrigidos nos
documentos** (sem mudança de contrato/escopo):

1. **Idempotência do sweep: UUID determinístico por dia, não `code="sweep:..."`
   literal** — o `PayoutCommand.withdrawalId` é `UUID` e o `code` do PSP é
   derivado dele (`PagarMePayoutProviderClient`: `POST /transfers` com `code` =
   withdrawal.id, Spec 47). O sweep usa
   `UUID.nameUUIDFromBytes("sweep:"+LocalDate)` → code **estável no dia** →
   replay idempotente. requirements (C2), design (D3), task (T1.1/T4.2).
2. **Roteamento pós-fallback da reconciliação/webhook** — o
   `PayoutProviderRouter` roteia o `createPayout`, mas `getPayout(externalReference)`
   **não identifica o provedor**; a coluna **`withdrawals.payout_provider` já
   existe** (migration V28, Spec 36 — `WithdrawalJpaEntity.payout_provider`):
   o provedor aceito é gravado nela e a reconciliação/webhook `/webhooks/payouts`
   roteiam por ela. requirements (C5), design (D5), task (T3.1).

**Âncoras validadas sem achado:** porta `PayoutProviderClient`
(`createPayout(PayoutCommand)` + `PayoutDestination` + `getPayout` + exceções
tipadas — sem `getBalance`, a porta `PspBalanceProvider` é genuinamente nova);
harness `PayoutProviderContractTest` (mesmo pacote do novo harness de saldo);
`PspProperties`/`application.yml` com blocos `payout-pagarme`/`payout-asaas`
(secret-key fail-fast, env overrides) — o fail-fast "payout-secret ==
charge-secret" é novo (hoje só valida presença); `MockPayoutProviderClient`
(simula falha); injetores do port (`WithdrawalService`, `MasterWithdrawalService`,
`AutoWithdrawRunnerService`, `WithdrawalReconciliationService` — o router
`@Primary` assume; a reconciliação já tem o withdrawal com `payout_provider`);
`JobExecutionService`/ShedLock/`job_runs` (Spec 53); outbox/`SecurityAlertPublisher`/
`NotificationProcessor` (padrão dos alertas `SWEEP_*`, Spec 35/64). Refs dos
frontmatters existem (specs 14/32/36/38/53/35/64/68, Visão V69, ADR-017). **Sem
migration nova** (sweep não persiste em `withdrawals`; reusa config + `job_runs`).
Pronta para `/spec-implement 92`.

## Auditoria (`/spec-audit 92` — 2026-08-14)

Critérios C1–C8 verificados contra o código real/testes/E2E — **1 achado de
código corrigido + 1 ajuste de doc**:

1. **Auth do `AsaasBalanceProvider` alinhada ao `access_token` (C1):** o adapter
   de saldo usava `Authorization: Bearer <token>` enquanto o
   `AsaasPixProviderClient` (cobrança, mesmo adquirente) usa o header
   `access_token` — dois padrões de auth para o Asaas (ambos válidos na API v3,
   mas inconsistência interna). Corrigido para `access_token` (padrão do
   adapter de cobrança); teste atualizado.
2. **requirements C4: fail-fast em QUALQUER perfil (não só prod):** a
   implementação (`requireDistinctSecrets` no `PspProperties.validate()`) roda
   em qualquer perfil — mais forte que o critério (prod). requirements.md
   ajustado para refletir a decisão da implementação.

**Verificados sem achado:** C1 (porta `PspBalanceProvider` + 3 adapters +
`PspBalanceContractTest`); C2 (scheduler ShedLock/JobExecutionService + UUID
determinístico por dia + skip ≤ 0 + FAILED + `SWEEP_FAILED`); C3 (buffer
default 0 + validação ≥ 0 + destination-key obrigatória quando enabled); C4
(payout-scoped + runbook §2c + fail-fast de isolação); C5 (`PayoutProviderRouter`
por prioridade + timeout/5xx→B + `withdrawals.payout_provider` pós-fallback +
reconciliação/webhook pelo provedor gravado); C6 (métricas `nyx_psp_balance_*`/
`nyx_psp_sweep_*` + alertas `SWEEP_EXPOSURE_HIGH`/`SWEEP_FAILED` — a falha do
sweep é coberta por `nyx_job_*` (job FAILED) + alerta, padrão Spec 53); C7
(binding `SweepProperties` + fail-fast); C8 (`mvn test` 1734/0/0 + E2E
`psp-sweep` 2/2 — poll por contagem, achado do E2E corrigido). **Sem achados
restantes — spec `audited`.**
