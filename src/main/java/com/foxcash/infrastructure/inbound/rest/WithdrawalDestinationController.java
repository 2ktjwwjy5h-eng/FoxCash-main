package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.WithdrawalDestinationUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: destinos de saque do lojista no portal (Spec 31),
 * {@code ROLE_MERCHANT_PORTAL}, {@code /api/v1/portal/destinations}. Privado por
 * lojista — o merchantId vem do principal (nunca do cliente); destino de outro
 * lojista responde 404 (sem revelar existência). A resposta sempre mascarada
 * (nunca o valor em claro).
 */
@RestController
@RequestMapping("/api/v1/portal/destinations")
public class WithdrawalDestinationController {

    private final WithdrawalDestinationUseCase useCase;

    public WithdrawalDestinationController(WithdrawalDestinationUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ResponseEntity<WithdrawalDestinationResponse> create(
            Authentication authentication,
            @Valid @RequestBody WithdrawalDestinationRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        WithdrawalDestinationUseCase.Command command = new WithdrawalDestinationUseCase.Command(
                request.type(), request.value(), request.bankCode(), request.agency(),
                request.account(), request.accountType(), request.label());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WithdrawalDestinationResponse.from(useCase.create(merchantId, command)));
    }

    @GetMapping
    public List<WithdrawalDestinationResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return useCase.list(merchantId).stream()
                .map(WithdrawalDestinationResponse::from)
                .toList();
    }

    @PatchMapping("/{destinationId}")
    public WithdrawalDestinationResponse updateLabel(
            Authentication authentication,
            @PathVariable UUID destinationId,
            @Valid @RequestBody WithdrawalDestinationUpdateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return WithdrawalDestinationResponse.from(
                useCase.updateLabel(merchantId, destinationId, request.label()));
    }

    @PostMapping("/{destinationId}/activate")
    public ResponseEntity<Void> activate(Authentication authentication,
                                         @PathVariable UUID destinationId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        useCase.setActive(merchantId, destinationId, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{destinationId}/deactivate")
    public ResponseEntity<Void> deactivate(Authentication authentication,
                                           @PathVariable UUID destinationId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        useCase.setActive(merchantId, destinationId, false);
        return ResponseEntity.noContent().build();
    }
}
