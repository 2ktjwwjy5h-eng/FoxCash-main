package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.in.PvsPaymentUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PvsLimitsResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PvsPayRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PvsPayResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: Pagamento via Saldo (Spec 120), role {@code ROLE_MERCHANT_PORTAL}
 * (principal = merchantId — nunca do body; impossível IDOR, mesmo padrão do
 * {@link AutoWithdrawController}).
 *
 * <p>{@code POST /api/v1/portal/pvs/pay} paga um Pix Copia e Cola ESTÁTICO com
 * o saldo (200 {@code PROCESSING}); {@code GET /api/v1/portal/pvs/limits} expõe
 * os tetos anti-abuso do dia. Erros de domínio → 400/409 com códigos
 * específicos via {@link GlobalExceptionHandler} (o controller valida só o
 * shape mínimo do {@code pixCode}: não-nulo, ≤ 1024).
 */
@RestController
@RequestMapping("/api/v1/portal/pvs")
public class PvsController {

    private final PvsPaymentUseCase useCase;

    public PvsController(PvsPaymentUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/pay")
    public ResponseEntity<PvsPayResponse> pay(Authentication authentication,
                                              @Valid @RequestBody PvsPayRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        PvsPaymentUseCase.Result result = useCase.pay(new PvsPaymentUseCase.PayCommand(
                merchantId, request.pixCode(),
                request.amount() == null ? null : Money.of(request.amount())));
        return ResponseEntity.ok(PvsPayResponse.from(result));
    }

    @GetMapping("/limits")
    public PvsLimitsResponse limits(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PvsLimitsResponse.from(useCase.limits(merchantId));
    }
}
