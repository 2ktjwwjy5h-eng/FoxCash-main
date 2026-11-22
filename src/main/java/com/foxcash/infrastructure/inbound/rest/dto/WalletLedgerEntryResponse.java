package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.wallet.WalletLedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de uma movimentação do ledger do wallet (Spec 27) — contrato
 * {@code {entryId, type, amount, balanceAfter, referenceType, referenceId,
 * createdAt}}. Valores financeiros com escala 2 (Money já normaliza).
 */
public record WalletLedgerEntryResponse(
        UUID entryId,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String referenceType,
        UUID referenceId,
        Instant createdAt) {

    public static WalletLedgerEntryResponse from(WalletLedgerEntry entry) {
        return new WalletLedgerEntryResponse(
                entry.id(),
                entry.type().name(),
                entry.amount().amount(),
                entry.balanceAfter().amount(),
                entry.referenceType(),
                entry.referenceId(),
                entry.createdAt());
    }
}
