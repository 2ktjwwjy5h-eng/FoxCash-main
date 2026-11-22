package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.audit.AuditSearchQuery;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.port.out.MasterAuditRepository;
import com.foxcash.infrastructure.inbound.rest.dto.AuditEntryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST: trilha de auditoria do master (Spec 37). Role
 * {@code ROLE_MASTER} (Spec 13). Somente-leitura e imutável — sem POST/PUT/
 * DELETE (append-only).
 *
 * <p>{@code GET /audit?merchantId=&action=&actorId=&from=&to=&page=&size=} →
 * entradas paginadas com filtros.
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterAuditController {

    private final MasterAuditRepository auditRepository;

    public MasterAuditController(MasterAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @GetMapping("/audit")
    public PageResponse<AuditEntryResponse> search(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuditSearchQuery query = new AuditSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseEnum(MasterAuditAction.class, action, "action"),
                QueryParamParser.parseUuid(actorId, "actorId"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(auditRepository.search(query), AuditEntryResponse::from);
    }
}
