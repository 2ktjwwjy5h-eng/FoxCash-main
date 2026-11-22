package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.CreatePixTransactionUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.CreatePixTransactionRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PixTransactionResponse;
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
 * Adaptador REST: endpoints de transacoes Pix.
 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final CreatePixTransactionUseCase createPixTransaction;
    private final QueryTransactionUseCase queryTransaction;
    private final MerchantAccessGuard accessGuard;

    public TransactionController(CreatePixTransactionUseCase createPixTransaction,
                                 QueryTransactionUseCase queryTransaction,
                                 MerchantAccessGuard accessGuard) {
        this.createPixTransaction = createPixTransaction;
        this.queryTransaction = queryTransaction;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<PixTransactionResponse> create(@Valid @RequestBody CreatePixTransactionRequest request) {
        accessGuard.requireMerchantAccess(request.merchantId());
        CreatePixTransactionUseCase.Result result = createPixTransaction.create(
                new CreatePixTransactionUseCase.Command(
                        request.merchantId(),
                        Money.of(request.amount()),
                        request.description(),
                        request.pixKey(),
                        request.buyerId(),
                        request.idempotencyKey()));
        PixTransaction transaction = queryTransaction.findById(result.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(result.transactionId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PixTransactionResponse.from(transaction));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<PixTransactionResponse> findById(@PathVariable UUID transactionId) {
        PixTransaction transaction = queryTransaction.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        accessGuard.requireMerchantAccess(transaction.merchantId());
        return ResponseEntity.ok(PixTransactionResponse.from(transaction));
    }

    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<PixTransactionResponse>> findByMerchant(@PathVariable UUID merchantId,
                                                                       @RequestParam(defaultValue = "200") int limit) {
        accessGuard.requireMerchantAccess(merchantId);
        // Auditoria 2026-08-05: lista limitada — nunca carrega o historico inteiro.
        int clamped = Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        var transactions = queryTransaction.findByMerchantId(merchantId, clamped).stream()
                .map(PixTransactionResponse::from)
                .toList();
        return ResponseEntity.ok(transactions);
    }

    /** Teto das listagens por lojista (auditoria 2026-08-05). */
    private static final int MAX_LIST_SIZE = 200;
}
