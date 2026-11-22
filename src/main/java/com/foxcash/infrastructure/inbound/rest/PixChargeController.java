package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PixChargeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PixChargeResponse;
import com.foxcash.infrastructure.security.MerchantAccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST: gera cobrancas Pix ({@code POST /api/v1/foxcash/pix/cob}).
 * Retorna QR Code (base64), Pix Copia e Cola, referencia externa e status.
 */
@RestController
@RequestMapping("/api/v1/foxcash/pix")
public class PixChargeController {

    private final CreatePixChargeUseCase createPixCharge;
    private final QueryTransactionUseCase queryTransaction;
    private final MerchantAccessGuard accessGuard;

    public PixChargeController(CreatePixChargeUseCase createPixCharge,
                               QueryTransactionUseCase queryTransaction,
                               MerchantAccessGuard accessGuard) {
        this.createPixCharge = createPixCharge;
        this.queryTransaction = queryTransaction;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/cob")
    public ResponseEntity<PixChargeResponse> create(@Valid @RequestBody PixChargeRequest request) {
        accessGuard.requireMerchantAccess(request.merchantId());
        CreatePixChargeUseCase.Result result = createPixCharge.create(
                new CreatePixChargeUseCase.Command(
                        request.merchantId(),
                        Money.of(request.amount()),
                        request.expirationSeconds(),
                        request.buyerId(),
                        null,
                        request.idempotencyKey(),
                        null, null, null));

        PixTransaction transaction = queryTransaction.findById(result.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(result.transactionId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(PixChargeResponse.from(transaction));
    }
}
