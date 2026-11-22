package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalSearchQuery;
import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.port.in.MasterWithdrawalControlUseCase;
import com.foxcash.domain.port.in.MasterWithdrawalUseCase;
import com.foxcash.domain.port.in.QueryWithdrawalUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.AdministrativeWithdrawalRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: controle master de saques (Spec 14) e saque administrativo
 * (Spec 32 — bloqueio "lacrado").
 *
 * <p>{@code POST /api/v1/master/merchants/{id}/withdrawals/block|release}
 * bloqueia/libera novos saques do lojista (204). {@code POST .../withdrawals/
 * administrative} recolhe valores em nome do lojista (201) — mesmo com bloqueio
 * ativo, nunca deixando saldo negativo. {@code GET /api/v1/master/withdrawals}
 * lista todas as saidas com filtros. Role {@code ROLE_MASTER} (Spec 13).
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterWithdrawalController {

    private final MasterWithdrawalControlUseCase control;
    private final MasterWithdrawalUseCase masterWithdrawal;
    private final QueryWithdrawalUseCase queryWithdrawal;
    private final ClientIpResolver clientIpResolver;

    public MasterWithdrawalController(MasterWithdrawalControlUseCase control,
                                      MasterWithdrawalUseCase masterWithdrawal,
                                      QueryWithdrawalUseCase queryWithdrawal,
                                      ClientIpResolver clientIpResolver) {
        this.control = control;
        this.masterWithdrawal = masterWithdrawal;
        this.queryWithdrawal = queryWithdrawal;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/merchants/{merchantId}/withdrawals/administrative")
    public ResponseEntity<WithdrawalResponse> administrativeWithdraw(
            @PathVariable UUID merchantId,
            @Valid @RequestBody AdministrativeWithdrawalRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        MasterWithdrawalUseCase.Result result = masterWithdrawal.administrativeWithdraw(
                merchantId, Money.of(request.amount()), request.destinationKey(), request.reason(),
                (UUID) authentication.getPrincipal(), clientIpResolver.resolve(httpRequest));
        Withdrawal withdrawal = queryWithdrawal.findById(result.withdrawalId())
                .orElseThrow(() -> new WithdrawalNotFoundException(result.withdrawalId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(WithdrawalResponse.from(withdrawal, result.walletBalance()));
    }

    @PostMapping("/merchants/{merchantId}/withdrawals/block")
    public ResponseEntity<Void> block(@PathVariable UUID merchantId,
                                      Authentication authentication,
                                      HttpServletRequest httpRequest) {
        control.block(merchantId, (UUID) authentication.getPrincipal(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/merchants/{merchantId}/withdrawals/release")
    public ResponseEntity<Void> release(@PathVariable UUID merchantId,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        control.release(merchantId, (UUID) authentication.getPrincipal(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/withdrawals")
    public PageResponse<WithdrawalResponse> search(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        WithdrawalSearchQuery query = new WithdrawalSearchQuery(
                QueryParamParser.parseUuid(merchantId, "merchantId"),
                QueryParamParser.parseEnum(WithdrawalStatus.class, status, "status"),
                QueryParamParser.parseInstant(from, "from"),
                QueryParamParser.parseInstant(to, "to"),
                QueryParamParser.clampPage(page),
                QueryParamParser.clampSize(size));
        return PageResponse.from(queryWithdrawal.search(query), WithdrawalResponse::from);
    }
}
