package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.BillingPreviewUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BillingPreviewResponse;
import com.foxcash.infrastructure.security.MerchantAccessGuard;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Adaptador REST: previsao de faturamento
 * ({@code GET /api/v1/billing/preview?merchantId=&month=yyyy-MM}).
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingPreviewUseCase billingPreview;
    private final MerchantAccessGuard accessGuard;

    public BillingController(BillingPreviewUseCase billingPreview, MerchantAccessGuard accessGuard) {
        this.billingPreview = billingPreview;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/preview")
    public BillingPreviewResponse preview(
            @RequestParam UUID merchantId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        accessGuard.requireMerchantAccess(merchantId);
        BillingPreviewUseCase.Preview preview = billingPreview.preview(merchantId, month);
        return BillingPreviewResponse.from(preview);
    }
}
