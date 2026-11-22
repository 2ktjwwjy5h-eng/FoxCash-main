package com.foxcash.domain.model.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta paginada da trilha de auditoria do master (Spec 37) — filtros
 * opcionais por lojista/acao/actor/período. Usada pelo
 * {@code GET /api/v1/master/audit}.
 */
public record AuditSearchQuery(
        UUID merchantId,
        MasterAuditAction action,
        UUID actorId,
        Instant from,
        Instant to,
        int page,
        int size) {

    public AuditSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }
}
