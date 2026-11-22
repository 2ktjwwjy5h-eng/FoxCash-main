package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.PlanPurchaseUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PlanPurchaseRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanPurchaseResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: compra de plano (Spec 49) — {@code POST
 * /api/v1/foxcash/plans/{id}/purchase} (API key do lojista, {@code ROLE_MERCHANT}).
 * Cria a cobrança Pix vinculada ao plano (mesmo contrato do {@code /foxcash/pix/cob}
 * + {@code planId}); o destinatário ({@code platform} + {@code platformUserId})
 * é persistido na transação para a entrega automática.
 *
 * <p>Provas de entrega (Spec 49+): o aceite dos termos ({@code termsAccepted})
 * vem no body; o IP (via {@link ClientIpResolver}) e o User-Agent são capturados
 * do request — evidências que sobrevivem até a entrega do plano.
 */
@RestController
@RequestMapping("/api/v1/foxcash/plans")
public class FoxPlanController {

    private final PlanPurchaseUseCase planPurchase;
    private final QueryTransactionUseCase queryTransaction;
    private final ClientIpResolver clientIpResolver;

    public FoxPlanController(PlanPurchaseUseCase planPurchase,
                             QueryTransactionUseCase queryTransaction,
                             ClientIpResolver clientIpResolver) {
        this.planPurchase = planPurchase;
        this.queryTransaction = queryTransaction;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/{id}/purchase")
    public ResponseEntity<PlanPurchaseResponse> purchase(Authentication authentication,
                                                         @PathVariable UUID id,
                                                         @RequestBody PlanPurchaseRequest request,
                                                         HttpServletRequest httpRequest) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        String userAgent = httpRequest.getHeader("User-Agent");
        String buyerDevice = (userAgent != null && userAgent.length() > 255)
                ? userAgent.substring(0, 255) : userAgent;
        Instant termsAcceptedAt = Boolean.TRUE.equals(request.termsAccepted())
                ? Instant.now() : null;
        PlanPurchaseUseCase.Result result = planPurchase.purchase(merchantId, id,
                new PlanPurchaseUseCase.PurchaseCommand(
                        request.expirationSeconds(),
                        request.buyerId(),
                        request.idempotencyKey(),
                        Platform.parse(request.platform()),
                        request.platformUserId(),
                        termsAcceptedAt,
                        clientIpResolver.resolve(httpRequest),
                        buyerDevice,
                        request.botId()));

        PixTransaction transaction = queryTransaction.findById(result.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(result.transactionId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(PlanPurchaseResponse.from(transaction));
    }
}
