package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.revenue.MasterLedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entry do ledger do master (Spec 34) — response de {@code GET /revenue/ledger}.
 */
public record MasterLedgerEntryResponse(
        UUID id,
        UUID merchantId,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String referenceType,
        UUID referenceId,
        Instant createdAt) {

    public static MasterLedgerEntryResponse from(MasterLedgerEntry entry) {
        return new MasterLedgerEntryResponse(
                entry.id(),
                entry.merchantId(),
                entry.type().name(),
                entry.amount().amount(),
                entry.balanceAfter().amount(),
                entry.referenceType(),
                entry.referenceId(),
                entry.createdAt());
    }
}
