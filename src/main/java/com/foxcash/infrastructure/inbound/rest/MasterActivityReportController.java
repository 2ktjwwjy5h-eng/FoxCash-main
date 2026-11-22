package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.MasterAdminGuard;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.port.in.PlatformActivityUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PlatformActivityResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: relatório unificado de atividades (Spec 41). **Rota
 * não-documentada publicamente** — somente o admin ({@code can_manage_masters})
 * consulta (403 {@code forbidden} sem a flag). Somente-leitura (append-only):
 * nenhuma rota de mutação.
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterActivityReportController {

    private final PlatformActivityUseCase activityUseCase;
    private final MasterAdminGuard adminGuard;

    public MasterActivityReportController(PlatformActivityUseCase activityUseCase,
                                          MasterAdminGuard adminGuard) {
        this.activityUseCase = activityUseCase;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/report/activity")
    public PageResponse<PlatformActivityResponse> search(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        adminGuard.requireAdmin((UUID) authentication.getPrincipal());
        PlatformActivityQuery query = new PlatformActivityQuery(
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.parseEnum(PlatformActorType.class, actorType, "actorType"),
                QueryParamParser.parseEnum(PlatformAction.class, action, "action"),
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(activityUseCase.query(query), PlatformActivityResponse::from);
    }
}
