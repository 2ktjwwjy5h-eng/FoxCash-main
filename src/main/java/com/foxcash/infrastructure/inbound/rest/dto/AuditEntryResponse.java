package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.audit.MasterAuditEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrada da trilha de auditoria do master (Spec 37) — response de
 * {@code GET /audit}. Imutável: sem rotas de edição/exclusão.
 */
public record AuditEntryResponse(
        UUID id,
        UUID actorId,
        String action,
        UUID merchantId,
        String referenceType,
        UUID referenceId,
        String before,
        String after,
        String reason,
        String ip,
        Instant createdAt) {

    public static AuditEntryResponse from(MasterAuditEntry entry) {
        return new AuditEntryResponse(
                entry.id(),
                entry.actorId(),
                entry.action().name(),
                entry.merchantId(),
                entry.referenceType(),
                entry.referenceId(),
                entry.beforeJson(),
                entry.afterJson(),
                entry.reason(),
                entry.ip(),
                entry.createdAt());
    }
}
