package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.GiftPresetUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.config.PortalProperties;
import com.foxcash.infrastructure.inbound.rest.dto.GiftPresetResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GiftPresetUpdateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PixChargeResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalChargeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PortalGiftChargeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: gerador de cobranca PIX do portal (Spec 23), role
 * {@code ROLE_MERCHANT_PORTAL} — cobranca avulsa, catalogo de Gift PIX
 * (presets configuraveis) e cobranca por preset/personalizado. Reusa o
 * {@link CreatePixChargeUseCase} (Spec 19) com triagem antifraude ativa.
 */
@RestController
@RequestMapping("/api/v1/portal/charges")
public class MerchantChargeController {

    private final CreatePixChargeUseCase createPixCharge;
    private final QueryTransactionUseCase queryTransaction;
    private final GiftPresetUseCase giftPresets;
    private final PortalProperties portalProperties;
    private final ProfileScopeResolver scopeResolver;

    public MerchantChargeController(CreatePixChargeUseCase createPixCharge,
                                    QueryTransactionUseCase queryTransaction,
                                    GiftPresetUseCase giftPresets,
                                    PortalProperties portalProperties,
                                    ProfileScopeResolver scopeResolver) {
        this.createPixCharge = createPixCharge;
        this.queryTransaction = queryTransaction;
        this.giftPresets = giftPresets;
        this.portalProperties = portalProperties;
        this.scopeResolver = scopeResolver;
    }

    @PostMapping
    public ResponseEntity<PixChargeResponse> create(Authentication authentication,
                                                    jakarta.servlet.http.HttpServletRequest httpRequest,
                                                    @Valid @RequestBody PortalChargeRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        validateAmount(request.amount());
        // Spec 124 (rota viva): perfil do header VALIDADO (ausente/inválido → null
        // → o serviço cai para o default do lojista — nunca vaza).
        UUID profileId = scopeResolver.resolveActiveProfileId(merchantId, httpRequest).orElse(null);
        CreatePixChargeUseCase.Result result = createPixCharge.create(
                new CreatePixChargeUseCase.Command(
                        merchantId, Money.of(request.amount()), request.expirationSeconds(),
                        request.buyerId(), request.description(), request.idempotencyKey(),
                        null, null, null, profileId));
        return created(result);
    }

    @GetMapping("/gift-presets")
    public List<GiftPresetResponse> giftPresets(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return giftPresets.presets(merchantId).stream()
                .map(GiftPresetResponse::from)
                .toList();
    }

    @PutMapping("/gift-presets/{presetId}")
    public ResponseEntity<Void> updatePreset(Authentication authentication,
                                             @PathVariable UUID presetId,
                                             @Valid @RequestBody GiftPresetUpdateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        giftPresets.setActive(merchantId, presetId, request.active());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/gift")
    public ResponseEntity<PixChargeResponse> gift(Authentication authentication,
                                                  @Valid @RequestBody PortalGiftChargeRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Money customAmount = request.amount() == null ? null : Money.of(request.amount());
        CreatePixChargeUseCase.Result result = giftPresets.giftCharge(merchantId,
                new GiftPresetUseCase.GiftChargeCommand(
                        request.presetId(), customAmount, request.expirationSeconds(),
                        request.buyerId(), request.description(), request.idempotencyKey()));
        return created(result);
    }

    private ResponseEntity<PixChargeResponse> created(CreatePixChargeUseCase.Result result) {
        PixTransaction transaction = queryTransaction.findById(result.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(result.transactionId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PixChargeResponse.from(transaction));
    }

    /** Valor invalido (≤ 0 ou acima do limite configuravel) → 400 {@code invalid_argument}. */
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
                || amount.compareTo(portalProperties.getChargeMaxAmount()) > 0) {
            throw new IllegalArgumentException("Valor invalido: deve ser entre 0.01 e "
                    + portalProperties.getChargeMaxAmount());
        }
    }
}
