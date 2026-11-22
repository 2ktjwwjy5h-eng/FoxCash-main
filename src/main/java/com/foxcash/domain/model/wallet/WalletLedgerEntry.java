package com.foxcash.domain.model.wallet;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entry imutável do ledger do wallet (Spec 27).
 *
 * <p>Uma movimentação registrada NUNCA é alterada (append-only): cada crédito/
 * débito do saldo do lojista gera uma entry na mesma transação do efeito
 * financeiro, com {@code balanceAfter} = saldo resultante (calculado sob lock
 * pessimista). {@code referenceType}/{@code referenceId} identificam a origem
 * (transação/saque/chargeback); {@code OPENING_BALANCE} usa referência nula.
 */
public record WalletLedgerEntry(
        UUID id,
        UUID merchantId,
        WalletLedgerEntryType type,
        Money amount,
        Money balanceAfter,
        String referenceType,
        UUID referenceId,
        Instant createdAt) {

    public WalletLedgerEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(balanceAfter, "balanceAfter must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Cria uma entry do ledger (usada pelo recorder e pelo backfill de abertura). */
    public static WalletLedgerEntry record(UUID merchantId, WalletLedgerEntryType type,
                                           Money amount, Money balanceAfter,
                                           String referenceType, UUID referenceId,
                                           Instant now) {
        return new WalletLedgerEntry(UUID.randomUUID(), merchantId, type, amount,
                balanceAfter, referenceType, referenceId, now);
    }
}
