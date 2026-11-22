package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.RiskSnapshotNotFoundException;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;
import com.foxcash.domain.port.in.MerchantStatusControlUseCase;
import com.foxcash.domain.port.in.QueryRiskUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantRiskResponse;
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

import java.util.UUID;

/**
 * Adaptador REST: monitoramento de risco e suspensão de lojistas (Spec 16).
 *
 * <p>Somente-leitura (consultas de risco) exceto os controles de status do
 * lojista (suspend/release — decisão do master). Role {@code ROLE_MASTER}.
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterRiskController {

    private final QueryRiskUseCase queryRisk;
    private final MerchantStatusControlUseCase statusControl;
    private final ClientIpResolver clientIpResolver;

    public MasterRiskController(QueryRiskUseCase queryRisk,
                                MerchantStatusControlUseCase statusControl,
                                ClientIpResolver clientIpResolver) {
        this.queryRisk = queryRisk;
        this.statusControl = statusControl;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/risk")
    public PageResponse<MerchantRiskResponse> risk(
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RiskSearchQuery query = new RiskSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(queryRisk.search(query), MerchantRiskResponse::from);
    }

    @GetMapping("/merchants/{merchantId}/risk")
    public MerchantRiskResponse latest(@PathVariable UUID merchantId) {
        MerchantRiskSnapshot snapshot = queryRisk.latestByMerchant(merchantId)
                .orElseThrow(() -> new RiskSnapshotNotFoundException(merchantId));
        return MerchantRiskResponse.from(snapshot);
    }

    @PostMapping("/merchants/{merchantId}/suspend")
    public ResponseEntity<Void> suspend(@PathVariable UUID merchantId,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        // Auditoria (Spec 37): actor = master.id() do principal; ip da requisicao.
        statusControl.suspend(merchantId, (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merchants/{merchantId}/release")
    public ResponseEntity<Void> release(@PathVariable UUID merchantId,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        statusControl.release(merchantId, (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
