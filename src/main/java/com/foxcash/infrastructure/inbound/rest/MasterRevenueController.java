package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.in.MasterRevenueUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterLedgerEntryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.RevenueSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Adaptador REST: receita/perda da plataforma (Spec 34). Role
 * {@code ROLE_MASTER} (Spec 13). Somente-leitura.
 *
 * <p>{@code GET /revenue/summary?from=&to=} → resumo por tipo + líquido;
 * {@code GET /revenue/ledger?merchantId=&type=&from=&to=&page=&size=} →
 * movimentações paginadas.
 */
@RestController
@RequestMapping("/api/v1/master/revenue")
public class MasterRevenueController {

    private final MasterRevenueUseCase revenue;

    public MasterRevenueController(MasterRevenueUseCase revenue) {
        this.revenue = revenue;
    }

    @GetMapping("/summary")
    public RevenueSummaryResponse summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        return RevenueSummaryResponse.from(revenue.summary(fromInstant, toInstant));
    }

    @GetMapping("/ledger")
    public PageResponse<MasterLedgerEntryResponse> ledger(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(revenue.ledger(
                        QueryParamParser.parseUuid(merchantId, "merchantId"),
                        QueryParamParser.parseEnum(MasterLedgerEntryType.class, type, "type"),
                        QueryParamParser.parseInstant(from, "from"),
                        QueryParamParser.parseInstant(to, "to"),
                        QueryParamParser.clampPage(page),
                        QueryParamParser.clampSize(size)),
                MasterLedgerEntryResponse::from);
    }
}
