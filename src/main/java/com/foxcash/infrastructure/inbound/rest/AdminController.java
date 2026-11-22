package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.in.AdminDashboardUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.DashboardSummaryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.InvoiceResponse;
import com.foxcash.infrastructure.inbound.rest.dto.IpLookupResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Adaptador REST: endpoints administrativos de consulta (dashboard).
 * Somente-leitura. Parâmetros inválidos são rejeitados com 400 (sem 500).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final AdminDashboardUseCase adminDashboard;

    public AdminController(AdminDashboardUseCase adminDashboard) {
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
                parseEnum(MerchantStatus.class, status, "status"),
                q,
                parseInstant(from, "from"),
                parseInstant(to, "to"),
                clampPage(page),
                clampSize(size));
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
                parseUuid(merchantId, "merchantId"),
                parseEnum(InvoiceStatus.class, status, "status"),
                parsePeriod(billingPeriod),
                clampPage(page),
                clampSize(size));
        return PageResponse.from(adminDashboard.searchInvoices(query), InvoiceResponse::from);
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse summary() {
        return DashboardSummaryResponse.from(adminDashboard.summary());
    }

    /**
     * Lookup de lojista(s) por endereço IP (Spec 67 — {@code !ip} do bot de
     * operações). IP inválido → 400 {@code invalid_argument}; sem associação →
     * 200 com {@code matches} vazio (o bot formata a mensagem).
     */
    @GetMapping("/lookup/ip/{ip}")
    public IpLookupResponse lookupByIp(@PathVariable String ip) {
        if (!QueryParamParser.isValidIp(ip)) {
            throw new IllegalArgumentException("Parâmetro inválido 'ip': '" + ip + "'");
        }
        return IpLookupResponse.from(adminDashboard.lookupByIp(ip));
    }

    private static int clampPage(int page) {
        return Math.max(page, DEFAULT_PAGE);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "'");
        }
    }

    private static Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "' (esperado ISO-8601)");
        }
    }

    private static BillingPeriod parsePeriod(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BillingPeriod.of(YearMonth.parse(value.trim()));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido 'billingPeriod': '" + value + "' (esperado yyyy-MM)");
        }
    }

    private static UUID parseUuid(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Parâmetro inválido '" + paramName + "': '" + value + "'");
        }
    }
}
