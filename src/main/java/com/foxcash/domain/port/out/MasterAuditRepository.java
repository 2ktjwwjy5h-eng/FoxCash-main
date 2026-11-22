package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.audit.AuditSearchQuery;
import com.foxcash.domain.model.audit.MasterAuditEntry;

/**
 * Porta de saida: persistencia da trilha de auditoria do master (Spec 37).
 * Append-only — apenas INSERT e consultas; NUNCA update/delete.
 */
public interface MasterAuditRepository {

    MasterAuditEntry save(MasterAuditEntry entry);

    /** Consulta paginada com filtros (merchantId/action/actorId/período). */
    PageResult<MasterAuditEntry> search(AuditSearchQuery query);
}
