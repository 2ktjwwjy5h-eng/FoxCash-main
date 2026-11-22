# Spec 88 — Design: Taxa de saque R$ 2,50

## Visão geral

A taxa de R$ 2,50 entra no fluxo de saque do lojista em 3 pontos determinísticos,
todos na **mesma transação** da Fase A (create) ou do settlement (FAILED):

```
CREATE (Fase A, transação própria):
  merchant.withdraw(amount + fee)          ← valida ACTIVE/bloqueio/saldo (valor + taxa)
  withdrawals.fee_amount = fee             ← persistido no saque (V51)
  wallet ledger: WITHDRAWAL_DEBIT(amount) + WITHDRAWAL_FEE_DEBIT(fee)
  master ledger: WITHDRAWAL_FEE(fee)       ← receita da plataforma (Spec 34)

FAILED (settlement, transação própria):
  merchant.credit(amount + fee)            ← estorno devolve TUDO
  wallet ledger: WITHDRAWAL_REVERSAL(amount) + WITHDRAWAL_FEE_REVERSAL(fee)
  master ledger: WITHDRAWAL_FEE_REVERSAL(fee)  ← reversão da receita (padrão SEC-29)

PAID (settlement): sem mudança — a receita já foi registrada no create.
ADMIN (create): fee = 0 — fluxo inalterado (adminWithdraw, ADMIN_RECOVERY).
```

## 1. Configuração

**`PricingProperties.java`** (`foxcash.pricing`):

```java
private BigDecimal withdrawalFee = new BigDecimal("2.50");
public BigDecimal getWithdrawalFee() { return withdrawalFee; }
public void setWithdrawalFee(BigDecimal withdrawalFee) { this.withdrawalFee = withdrawalFee; }
```

**`application.yml`**:

```yaml
foxcash:
  pricing:
    transaction-fee: 0.50
    monthly-fee: 69.95
    withdrawal-fee: 2.50   # Spec 88
    max-pending-charges: 100
```

Injeção: `WithdrawalService` (manual) e o job de auto-withdraw (Spec 70) recebem
`PricingProperties` e repassam `withdrawalFee` ao persister. O **valor nunca é
hardcoded no service** (default apenas na propriedade).

## 2. Migration V51__withdrawal_fee.sql

```sql
-- Spec 88: taxa de saque (fee_amount no saque — registro financeiro estável).
ALTER TABLE withdrawals
    ADD COLUMN fee_amount NUMERIC(15,2) NOT NULL DEFAULT 0.00;
```

Default `0.00` cobre os saques administrativos (isentos) e qualquer dado legado.

## 3. Domínio

**`Withdrawal.java`** — novo campo imutável `Money fee`:

- `processing(merchantId, amount, fee, destinationId, destinationKeySnapshot, type, reason)`
  — novo parâmetro `fee` (valida `Objects.requireNonNull`).
- `restore(...)` ganha `Money fee` no final da assinatura.
- Todas as transições (`submitToPsp`/`complete`/`fail`) preservam `fee`.
- Novo accessor `fee()`.

**`Merchant.java`** — sem mudança de método: o persister chama
`merchant.withdraw(amount.add(fee))`; a validação existente (ACTIVE, bloqueio,
`saldo >= amount + fee`) cobre a taxa automaticamente.

## 4. Ledgers (enums aditivos)

**`WalletLedgerEntryType.java`**:

```java
/** Taxa de saque (Spec 88) — débito no saldo (referência = withdrawal). */
WITHDRAWAL_FEE_DEBIT,
/** Devolução da taxa de saque FAILED (Spec 88) — crédito no saldo. */
WITHDRAWAL_FEE_REVERSAL;
```

**`MasterLedgerEntryType.java`**:

```java
/** Taxa de saque (Spec 88) — R$ 2,50 por saque do lojista. */
WITHDRAWAL_FEE,
/** Reversão da taxa de saque que falhou (Spec 88, padrão SEC-29). */
WITHDRAWAL_FEE_REVERSAL;
```

## 5. Persister — `WithdrawalProcessingPersister.java`

**`createMerchantWithdrawal(merchantId, amount, destination, blockOnDebt, fee)`**:

```java
merchant.withdraw(amount.add(fee));                    // saldo >= valor + taxa
Withdrawal withdrawal = Withdrawal.processing(merchantId, amount, fee,
        destination.id(), destination.snapshotValue(), MERCHANT, null);
Withdrawal saved = withdrawalRepository.save(withdrawal);
ledgerRecorder.record(merchant, WITHDRAWAL_DEBIT, saved.amount(), "WITHDRAWAL", saved.id());
ledgerRecorder.record(merchant, WITHDRAWAL_FEE_DEBIT, saved.fee(), "WITHDRAWAL", saved.id());
masterLedgerRecorder.record(merchantId, WITHDRAWAL_FEE, saved.fee(), "WITHDRAWAL", saved.id());
```

**`createMerchantAutoWithdrawal(merchantId, destination, threshold, blockOnDebt, fee)`**:

- Gate de limiar inalterado (`wallet_balance >= threshold`).
- `Money amount = merchant.walletBalance().subtract(fee)` — saca todo o saldo
  **menos** a taxa (paga a taxa do próprio saldo; wallet zera).
- `amount <= 0` (saldo não cobre a taxa) → `AutoWithdrawBelowThresholdException`
  (skip silencioso — mesma semântica do limiar).
- Depois: mesmo fluxo do manual (withdraw, ledger duplo, master WITHDRAWAL_FEE).

**`createAdministrativeWithdrawal(...)`** — **sem taxa**: `fee = Money.ZERO`,
sem `WITHDRAWAL_FEE_DEBIT`, sem master `WITHDRAWAL_FEE`. Fluxo inalterado.

## 6. Settlement — `WithdrawalSettlementService.settleOutcome`

**FAILED** (merchant com taxa):

```java
merchant.credit(failed.amount().add(failed.fee()));            // valor + taxa
ledgerRecorder.record(merchant, WITHDRAWAL_REVERSAL, failed.amount(), "WITHDRAWAL", failed.id());
ledgerRecorder.record(merchant, WITHDRAWAL_FEE_REVERSAL, failed.fee(), "WITHDRAWAL", failed.id());
if (failed.fee() > 0) {                                        // merchant: reverte receita
    masterLedgerRecorder.record(merchantId, WITHDRAWAL_FEE_REVERSAL, failed.fee(), "WITHDRAWAL", failed.id());
}
```

**FAILED** (admin, fee = 0): fluxo atual inalterado (`ADMIN_RECOVERY_REVERSAL`).
**PAID**: inalterado (receita já registrada no create).

## 7. Receita do master — `MasterRevenueService` + DTOs

**`MasterRevenueUseCase.RevenueSummary`**: novo campo `BigDecimal withdrawalFees`
(após `adminRecoveries`).

**`MasterRevenueService.summary`**:

```java
BigDecimal withdrawalFees = repository.sumAmountByTypeBetween(WITHDRAWAL_FEE, start, end)
        .subtract(repository.sumAmountByTypeBetween(WITHDRAWAL_FEE_REVERSAL, start, end));
// total = transactionFees + monthlyFees + recoveries + adminRecoveries + withdrawalFees + losses
// net   = transactionFees + monthlyFees + recoveries + adminRecoveries + withdrawalFees - losses
```

**`RevenueSummaryResponse`**: novo campo `withdrawalFees` (aditivo, mesmo
formato — o contrato JSON ganha a chave sem quebrar consumidores).

## 8. Resposta do saque — `WithdrawalResponse`

Novo campo `BigDecimal fee` (aditivo, entre `amount` e `status`) — preenchido de
`withdrawal.fee().amount()`. Disponível no POST (create) e nas listagens.

## 9. Testes

**Unitários:**
- `MerchantTest` — `withdraw(amount + fee)` valida saldo incluindo a taxa.
- `WithdrawalProcessingPersisterTest` — saldo pós-débito (`200 − 50 − 2,50 =
  147,50`), entries `WITHDRAWAL_DEBIT` + `WITHDRAWAL_FEE_DEBIT` + master
  `WITHDRAWAL_FEE`; auto-withdraw saca `saldo − 2,50` e zera; saldo insuficiente
  considerando a taxa → 409; admin sem taxa.
- `WithdrawalSettlementServiceTest` — FAILED devolve `valor + taxa`, entries
  `WITHDRAWAL_REVERSAL` + `WITHDRAWAL_FEE_REVERSAL` + master
  `WITHDRAWAL_FEE_REVERSAL`; idempotência; admin inalterado (SEC-29).
- `WithdrawalServiceTest` — `result.walletBalance()` com a taxa debitada.
- `MasterWithdrawalServiceTest` — admin sem taxa (walletBalance inalterado).
- `MasterRevenueServiceTest` — `withdrawalFees` = Σ fee − Σ reversão; `net`
  inclui o bucket.
- `WalletLedgerRecorderTest`/`WithdrawalTest` — novos tipos/campo fee.

**E2E (`merchant-journey.spec.js`):** atualizar asserções de saldo —
- **13.7**: `200 − 50 − 2,50 = 147,50`; **13.10** (admin): `147,50 − 30 =
  117,50`.
- **14**: `14.3` → `117,50 − 70,95 = 46,55`; `14.5` parcial → debita `46,55`,
  remaining `53,45`; `14.8` (credit 100 + admin 20) → `80,00`; `14.9`
  re-cobrança `53,45` → saldo `26,55`; `14.10` (saque 20) → `26,55 − 20 − 2,50 =
  4,05`.
- **16**: `balanceBefore − 30 − 2,50` (create) e saldo estável no PAID.
- **17**: FAILED devolve tudo — `balanceBefore` (asserção atual já bate);
  ledger `WITHDRAWAL_FEE_REVERSAL` presente.
- **psp-flow** e **reliability**: conferir asserções de saldo após saque.
- **master-console-stepup** cenário 5 (saque administrativo): sem taxa.

## 10. Documentação

- **README.md**: seção de precificação → "R$ 0,50 por transação aprovada +
  R$ 69,95 mensais + **R$ 2,50 por saque**".
- **docs/politicas-uso.md**: nota na seção do gateway/vendedor sobre a taxa por
  saque (devolvida se o saque falhar).
