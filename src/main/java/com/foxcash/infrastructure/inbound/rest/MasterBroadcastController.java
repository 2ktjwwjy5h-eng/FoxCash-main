package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.MasterBroadcastCommandService;
import com.foxcash.application.service.MasterBroadcastQueryService;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.infrastructure.inbound.rest.dto.MasterBroadcastDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MasterBroadcastSummary;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: auditoria de broadcasts no Master Console (Spec 89).
 *
 * <p>Leitura (list/detail) para qualquer master ativo (espelho read-only da
 * plataforma inteira — cross-merchant); o force-cancel exige nível OPERATOR
 * (Spec 69) e gera trilha {@code BROADCAST_FORCE_CANCELLED} (Spec 37). Rotas
 * sob {@code /api/v1/master} — role {@code ROLE_MASTER}.
 */
@RestController
@RequestMapping("/api/v1/master/broadcasts")
public class MasterBroadcastController {

    private final MasterBroadcastQueryService queryService;
    private final MasterBroadcastCommandService commandService;
    private final ClientIpResolver clientIpResolver;

    public MasterBroadcastController(MasterBroadcastQueryService queryService,
                                     MasterBroadcastCommandService commandService,
                                     ClientIpResolver clientIpResolver) {
        this.queryService = queryService;
        this.commandService = commandService;
        this.clientIpResolver = clientIpResolver;
    }

    /** Listagem paginada cross-merchant com filtros opcionais (sem content). */
    @GetMapping
    public PageResponse<MasterBroadcastSummary> list(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        BroadcastRepository.MasterBroadcastSearchQuery query = new BroadcastRepository.MasterBroadcastSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseUuid(botId, "botId"),
                QueryParamParser.parseEnum(BroadcastStatus.class, status, "status"),
                QueryParamParser.parseEnum(BroadcastMode.class, mode, "mode"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        PageResult<MasterBroadcastQueryService.MasterBroadcastView> result = queryService.list(query);
        return PageResponse.from(result, MasterBroadcastSummary::from);
    }

    /** Detalhe completo (conteúdo + logs por destinatário) — 404 se inexistente. */
    @GetMapping("/{id}")
    public MasterBroadcastDetail detail(@PathVariable UUID id) {
        MasterBroadcastQueryService.MasterBroadcastView view = queryService.detail(id);
        List<BroadcastLog> logs = queryService.logs(id);
        return MasterBroadcastDetail.from(view, logs);
    }

    /** Force-cancel (OPERATOR): PENDING → FAILED com trilha de auditoria. */
    @PostMapping("/{id}/force-cancel")
    public ResponseEntity<MasterBroadcastDetail> forceCancel(@PathVariable UUID id,
                                                             Authentication authentication,
                                                             HttpServletRequest httpRequest) {
        UUID operatorId = (UUID) authentication.getPrincipal();
        String ip = clientIpResolver.resolve(httpRequest);
        Broadcast updated = commandService.forceCancel(id, operatorId, ip);
        MasterBroadcastQueryService.MasterBroadcastView view =
                queryService.detail(updated.id());
        List<BroadcastLog> logs = queryService.logs(updated.id());
        return ResponseEntity.ok(MasterBroadcastDetail.from(view, logs));
    }
}
