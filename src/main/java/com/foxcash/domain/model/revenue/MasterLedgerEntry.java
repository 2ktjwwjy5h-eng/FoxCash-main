package com.foxcash.domain.model.revenue;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entry imutavel do ledger do master (Spec 34).
 *
 * <p>Receita/perda da plataforma registrada na mesma transacao do evento que a
 * originou: fatura paga (33), receivable de estorno pago, saque administrativo
 * (32) e write-off de receivable. {@code balanceAfter} e o saldo da plataforma
 * para aquele lojista apos a entry (soma cumulativa por lojista, serializada
 * pelo lock do merchant). {@code referenceType}/{@code referenceId} identificam
 * a origem (INVOICE/RECEIVABLE/WITHDRAWAL).
 */
public record MasterLedgerEntry(
        UUID id,
        UUID merchantId,
        MasterLedgerEntryType type,
        Money amount,
        Money balanceAfter,
        String referenceType,
        UUID referenceId,
        Instant createdAt) {

    public MasterLedgerEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(balanceAfter, "balanceAfter must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Cria uma entry do ledger do master (usada pelo recorder). */
    public static MasterLedgerEntry record(UUID merchantId, MasterLedgerEntryType type,
                                           Money amount, Money balanceAfter,
                                           String referenceType, UUID referenceId,
                                           Instant now) {
        return new MasterLedgerEntry(UUID.randomUUID(), merchantId, type, amount,
                balanceAfter, referenceType, referenceId, now);
    }
}
