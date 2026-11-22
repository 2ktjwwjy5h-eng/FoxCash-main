package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.port.in.GenerateInvoiceUseCase;
import com.foxcash.domain.port.in.QueryInvoiceUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.GenerateInvoiceRequest;
import com.foxcash.infrastructure.inbound.rest.dto.InvoiceResponse;
import com.foxcash.infrastructure.security.MerchantAccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: endpoints de faturamento.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/invoices")
public class InvoiceController {

    private final GenerateInvoiceUseCase generateInvoice;
    private final QueryInvoiceUseCase queryInvoice;
    private final MerchantAccessGuard accessGuard;

    public InvoiceController(GenerateInvoiceUseCase generateInvoice,
                             QueryInvoiceUseCase queryInvoice,
                             MerchantAccessGuard accessGuard) {
        this.generateInvoice = generateInvoice;
        this.queryInvoice = queryInvoice;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> generate(@PathVariable UUID merchantId,
                                                    @Valid @RequestBody GenerateInvoiceRequest request) {
        accessGuard.requireMerchantAccess(merchantId);
        Invoice invoice = generateInvoice.generateForPeriod(merchantId, request.period());
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.from(invoice));
    }

    @GetMapping
    public ResponseEntity<List<InvoiceResponse>> listByMerchant(@PathVariable UUID merchantId,
                                                                @RequestParam(defaultValue = "200") int limit) {
        accessGuard.requireMerchantAccess(merchantId);
        // Auditoria 2026-08-05: lista limitada — nunca carrega o historico inteiro.
        int clamped = Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        var invoices = queryInvoice.findByMerchantId(merchantId, clamped).stream()
                .map(InvoiceResponse::from)
                .toList();
        return ResponseEntity.ok(invoices);
    }

    /** Teto das listagens por lojista (auditoria 2026-08-05). */
    private static final int MAX_LIST_SIZE = 200;
}
