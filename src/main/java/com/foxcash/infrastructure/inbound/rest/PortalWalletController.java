package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.port.in.MerchantWalletUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PortalCashFlowResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalMonthlySummaryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalTimeSeriesResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalWalletBalanceResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: carteira/financeiro do lojista no portal (Spec 22), role
 * {@code ROLE_MERCHANT_PORTAL}. Somente-leitura. Parâmetros invalidos → 400
 * {@code invalid_argument} (mesmo contrato do QueryParamParser).
 */
@RestController
@RequestMapping("/api/v1/portal/wallet")
public class PortalWalletController {

    private final MerchantWalletUseCase wallet;

    public PortalWalletController(MerchantWalletUseCase wallet) {
        this.wallet = wallet;
    }

    @GetMapping("/balance")
    public PortalWalletBalanceResponse balance(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PortalWalletBalanceResponse.from(wallet.balance(merchantId));
    }

    @GetMapping("/monthly-summary")
    public PortalMonthlySummaryResponse monthlySummary(
            Authentication authentication,
            @RequestParam String month) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        BillingPeriod period = QueryParamParser.parsePeriod(month);
        return PortalMonthlySummaryResponse.from(wallet.monthlySummary(merchantId, period));
    }

    @GetMapping("/weekly-inflows")
    public List<PortalTimeSeriesResponse> weeklyInflows(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return wallet.weeklyInflows(merchantId).stream()
                .map(PortalTimeSeriesResponse::from)
                .toList();
    }

    @GetMapping("/cash-flow")
    public PortalCashFlowResponse cashFlow(
            Authentication authentication,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        Instant[] window = monthWindow(fromInstant, toInstant);
        return PortalCashFlowResponse.from(wallet.cashFlow(merchantId, window[0], window[1]));
    }

    /** Janela padrão: mês corrente (UTC) quando from/to ausentes. */
    private Instant[] monthWindow(Instant from, Instant to) {
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = from != null ? from : today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to != null ? to : now;
        return new Instant[]{start, end};
    }
}
