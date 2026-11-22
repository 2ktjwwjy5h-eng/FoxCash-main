package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MasterDashboardUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterEarningsResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MasterFeesResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PixTransactionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: dashboard do master (Spec 15) — transacoes, ganhos e taxas.
 * Somente-leitura; role {@code ROLE_MASTER} (Spec 13). Parâmetros invalidos
 * → 400 {@code invalid_argument} (mesmo contrato do AdminController).
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterDashboardController {

    private final MasterDashboardUseCase masterDashboard;

    public MasterDashboardController(MasterDashboardUseCase masterDashboard) {
        this.masterDashboard = masterDashboard;
    }

    @GetMapping("/transactions")
    public PageResponse<PixTransactionResponse> transactions(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MasterTransactionSearchQuery query = new MasterTransactionSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseEnum(TransactionStatus.class, status, "status"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(masterDashboard.transactions(query), PixTransactionResponse::from);
    }

    @GetMapping("/merchants/{merchantId}/earnings")
    public MasterEarningsResponse earnings(@PathVariable UUID merchantId) {
        return MasterEarningsResponse.from(masterDashboard.earnings(merchantId));
    }

    @GetMapping("/merchants/{merchantId}/fees")
    public MasterFeesResponse fees(@PathVariable UUID merchantId,
                                   @RequestParam String month) {
        BillingPeriod period = QueryParamParser.parsePeriod(month);
        return MasterFeesResponse.from(masterDashboard.fees(merchantId, period));
    }
}
