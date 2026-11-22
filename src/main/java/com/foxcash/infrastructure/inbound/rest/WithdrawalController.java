package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.port.in.QueryWithdrawalUseCase;
import com.foxcash.domain.port.in.WithdrawalUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalResponse;
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
 * Adaptador REST: saques do lojista (Spec 14).
 *
 * <p>{@code POST /api/v1/merchants/{merchantId}/withdrawals} realiza um saque
 * automatico (201); {@code GET .../withdrawals} lista os proprios saques.
 * Ambos exigem a chave do proprio lojista ({@link MerchantAccessGuard}).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/withdrawals")
public class WithdrawalController {

    private final WithdrawalUseCase withdrawalUseCase;
    private final QueryWithdrawalUseCase queryWithdrawal;
    private final MerchantAccessGuard accessGuard;

    public WithdrawalController(WithdrawalUseCase withdrawalUseCase,
                                QueryWithdrawalUseCase queryWithdrawal,
                                MerchantAccessGuard accessGuard) {
        this.withdrawalUseCase = withdrawalUseCase;
        this.queryWithdrawal = queryWithdrawal;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<WithdrawalResponse> withdraw(@PathVariable UUID merchantId,
                                                       @Valid @RequestBody WithdrawalRequest request) {
        accessGuard.requireMerchantAccess(merchantId);
        WithdrawalUseCase.Result result = withdrawalUseCase.withdraw(
                new WithdrawalUseCase.Command(merchantId, Money.of(request.amount()), request.destinationId()));
        Withdrawal withdrawal = queryWithdrawal.findById(result.withdrawalId())
                .orElseThrow(() -> new WithdrawalNotFoundException(result.withdrawalId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(WithdrawalResponse.from(withdrawal, result.walletBalance()));
    }

    @GetMapping
    public ResponseEntity<List<WithdrawalResponse>> list(@PathVariable UUID merchantId,
                                                         @RequestParam(defaultValue = "200") int limit) {
        accessGuard.requireMerchantAccess(merchantId);
        // Auditoria 2026-08-05: lista limitada — nunca carrega o historico inteiro.
        int clamped = Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        List<WithdrawalResponse> withdrawals = queryWithdrawal.findByMerchantId(merchantId, clamped).stream()
                .map(WithdrawalResponse::from)
                .toList();
        return ResponseEntity.ok(withdrawals);
    }

    /** Teto das listagens por lojista (auditoria 2026-08-05). */
    private static final int MAX_LIST_SIZE = 200;
}
