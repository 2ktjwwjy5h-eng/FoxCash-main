package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReconciliationIssueResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReconciliationRunResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST: reconciliação e integridade do saldo (Spec 30). Role
 * {@code ROLE_MASTER} (Spec 13).
 *
 * <p>{@code POST /reconciliation/run} → executa a validação de todos os
 * lojistas na hora e retorna o resumo; {@code GET /reconciliation/issues} →
 * divergências paginadas com filtros {@code merchantId}/{@code status}
 * (400 {@code invalid_argument} em parâmetro inválido).
 */
@RestController
@RequestMapping("/api/v1/master/reconciliation")
public class ReconciliationController {

    private final WalletReconciliationUseCase reconciliation;

    public ReconciliationController(WalletReconciliationUseCase reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/run")
    public ReconciliationRunResponse run() {
        return ReconciliationRunResponse.from(reconciliation.run());
    }

    @GetMapping("/issues")
    public PageResponse<ReconciliationIssueResponse> issues(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(
                reconciliation.findIssues(
                        QueryParamParser.parseUuid(merchantId, "merchantId"),
                        QueryParamParser.parseEnum(ReconciliationIssueStatus.class, status, "status"),
                        QueryParamParser.clampPage(page),
                        QueryParamParser.clampSize(size)),
                ReconciliationIssueResponse::from);
    }
}
