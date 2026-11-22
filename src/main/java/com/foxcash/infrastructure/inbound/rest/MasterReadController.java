package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.in.AdminDashboardUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.InvoiceResponse;
import com.foxcash.infrastructure.inbound.rest.dto.IpLookupResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST: espelhos READ-ONLY sob {@code /api/v1/master} (Spec 71, fatia
 * 1) — o master autenticado precisa navegar ate o detalhe de um lojista (earnings/
 * fees/risco/ledger/receivables), mas os dados so existem sob
 * {@code /api/v1/admin/**} (ROLE_ADMIN, que o master nao alcanca). Reusa o
 * {@code AdminDashboardUseCase} como esta (o use case nao tem role embutida — a
 * fronteira de role e o controller); contratos admin INALTERADOS.
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterReadController {

    private final AdminDashboardUseCase adminDashboard;

    public MasterReadController(AdminDashboardUseCase adminDashboard) {
        this.adminDashboard = adminDashboard;
    }

    @GetMapping("/merchants")
    public PageResponse<MerchantResponse> merchants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MerchantSearchQuery query = new MerchantSearchQuery(
                QueryParamParser.parseEnum(MerchantStatus.class, status, "status"),
                q,
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(adminDashboard.searchMerchants(query), MerchantResponse::from);
    }

    @GetMapping("/invoices")
    public PageResponse<InvoiceResponse> invoices(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String billingPeriod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        InvoiceSearchQuery query = new InvoiceSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseEnum(InvoiceStatus.class, status, "status"),
                QueryParamParser.parsePeriod(billingPeriod),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(adminDashboard.searchInvoices(query), InvoiceResponse::from);
    }

    @GetMapping("/lookup/ip/{ip}")
    public IpLookupResponse lookupByIp(@PathVariable String ip) {
        if (!QueryParamParser.isValidIp(ip)) {
            throw new IllegalArgumentException("Parâmetro inválido 'ip': '" + ip + "'");
        }
        return IpLookupResponse.from(adminDashboard.lookupByIp(ip));
    }
}
