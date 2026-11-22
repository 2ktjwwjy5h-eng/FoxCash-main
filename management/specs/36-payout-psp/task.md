# Spec 36 — Task breakdown

Status: `implemented` (2026-08-04). Implementação concluída — checklist abaixo.

## Implementação

- [x] Migration `V28__withdrawal_payout_state.sql` (o número avançou: V25 ficou
      planejada; V26 = `master_audit_log`, V27 = `merchant_webhooks`):
      `withdrawals` ganha `external_reference` (VARCHAR 100), `payout_provider`
      (VARCHAR 30), `failure_reason` (VARCHAR 255), `failed_at` (TIMESTAMP) +
      índice `(status, created_at)` para a reconciliação. O `status` existente
      (VARCHAR) passa a usar PROCESSING/COMPLETED/FAILED via enum (default
      COMPLETED preserva o histórico).
- [x] `WithdrawalStatus` (PROCESSING/COMPLETED/FAILED + REJECTED reservado) +
      `Withdrawal` evolui: `processing(...)` (nasce PROCESSING), `submitToPsp
      (externalReference, provider)`, `complete(now)`, `fail(reason, now)` com
      guards de estado (transições retornam novas instâncias — imutável).
- [x] `PayoutProviderClient` (port out): `createPayout(PayoutCommand)` +
      `getPayout(externalReference)` (reconciliação) + records
      `PayoutCommand`/`PayoutDestination`/`PayoutResult` + enum `PayoutStatus`.
- [x] `MockPayoutProviderClient` (`@ConditionalOnProperty payout-provider=mock`,
      matchIfMissing=true): externalReference `PO<32 hex>`; falha simulada via
      `foxcash.psp.mock-payout-failure` (createPayout lança → rollback; getPayout
      → FAILED).
- [x] `PspProperties` estendido: `payout-provider`, `mock-payout-failure`,
      `payout-webhook` (reusa `Webhook`), `payout-reconciliation`
      (cron/pending-since-hours); `@PostConstruct` valida o segredo do
      payout-webhook (fail-fast); provider desconhecido → bean ausente (fail-fast
      no boot, padrão Spec 10/11).
- [x] `WithdrawalService`/`MasterWithdrawalService` (Spec 32/36): após debitar
      (mesma transação), chamam `createPayout` e gravam saque **`PROCESSING`** +
      `external_reference` + `payout_provider`; erro síncrono do provider →
      rollback (nada criado). O evento `WITHDRAWAL_COMPLETED` (Spec 35) passa a
      ser publicado na **conclusão** do saque (não na solicitação).
- [x] `WithdrawalSettlementService` (novo): `settleByExternalReference` sob lock
      `findByExternalReferenceForUpdate` + gate de status (idempotente);
      `PAID` → `complete` + publica `WITHDRAWAL_COMPLETED`; `FAILED` → `fail` +
      estorno (`merchant.credit` + ledger `WITHDRAWAL_REVERSAL` na mesma
      transação); externalReference desconhecido → 404 `withdrawal_not_found`.
- [x] `PayoutWebhookController` (`POST /webhooks/payouts`, público — assinatura
      HMAC via `WebhookSignatureFilter` generalizado p/ os dois paths, segredo
      próprio `foxcash.psp.payout-webhook.*`) + DTO `PayoutWebhookRequest`.
- [x] `WithdrawalReconciliationService` + scheduler (ShedLock
      `foxcash-payout-reconciliation`, cron `foxcash.psp.payout-reconciliation.cron`
      default 04:30 UTC): encerra `PROCESSING` órfãos
      (`created_at < now - pending-since-hours`) consultando `getPayout` do PSP
      e delegando ao settlement (sem duplicar estorno).
- [x] `WithdrawalJpaEntity`/mapper (colunas novas; snapshot criptografado da
      Spec 32 mantido) + `WithdrawalRepository`/adapter
      (`findByExternalReferenceForUpdate` com `@Lock(PESSIMISTIC_WRITE)`,
      `findProcessingOlderThan`).
- [x] `WithdrawalResponse` expõe `externalReference`/`failureReason`/`failedAt`
      + estado real (PROCESSING/COMPLETED/FAILED).

## Verificação

- [x] `WithdrawalSettlementServiceTest`: PAID → COMPLETED (publica evento); FAILED
      → estorno único (WITHDRAWAL_REVERSAL no ledger + credit); re-entrega → no-op;
      externalReference desconhecido → 404.
- [x] `WithdrawalServiceTest`: saque válido nasce `PROCESSING` com
      `external_reference` (`PO<32 hex>`) + payoutProvider; provider lança erro →
      rollback (saldo intacto, sem saque); bloqueios/destino/dívida (Spec 32/33)
      continuam 409/404.
- [x] `WithdrawalReconciliationServiceTest`: órfão PROCESSING → PSP diz PAID →
      COMPLETED (settle delegado); PSP diz FAILED → estorno; sem órfãos → no-op;
      re-execução não duplica.
- [x] `MockPayoutProviderClientTest`: `mock-payout-failure=true` → falha; senão
      externalReference presente (`PO` + 32 hex); `getPayout` reflete a flag.
- [x] `WebhookSignatureFilterTest` ampliado: `/webhooks/payouts` exige o próprio
      segredo (rejeita o do pix; aceita o seu).
- [x] E2E: cenário 13.7 atualizado (saque nasce PROCESSING + completa via
      webhook de saída) + **cenário 16** (PROCESSING → COMPLETED via webhook
      PAID) e **cenário 17** (FAILED → estorno automático com WITHDRAWAL_REVERSAL
      no ledger) — suite **18/18** sem regressão.
- [x] `mvn test` verde (**313 testes, 0 falhas** — 299 + 14 novos).
