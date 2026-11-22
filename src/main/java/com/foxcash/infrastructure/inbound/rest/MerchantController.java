package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.QueryMerchantUseCase;
import com.foxcash.domain.port.in.RegisterMerchantUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.CreateMerchantRequest;
import com.foxcash.infrastructure.security.MerchantAccessGuard;
import com.foxcash.infrastructure.security.TurnstileVerifier;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantRegisteredResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: endpoints de lojistas.
 */
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final RegisterMerchantUseCase registerMerchant;
    private final QueryMerchantUseCase queryMerchant;
    private final MerchantAccessGuard accessGuard;
    private final TurnstileVerifier turnstileVerifier;

    public MerchantController(RegisterMerchantUseCase registerMerchant,
                              QueryMerchantUseCase queryMerchant,
                              MerchantAccessGuard accessGuard,
                              TurnstileVerifier turnstileVerifier) {
        this.registerMerchant = registerMerchant;
        this.queryMerchant = queryMerchant;
        this.accessGuard = accessGuard;
        this.turnstileVerifier = turnstileVerifier;
    }

    @PostMapping
    public ResponseEntity<MerchantRegisteredResponse> register(@Valid @RequestBody CreateMerchantRequest request) {
        // Spec 63 (Fase C): bot mitigation no cadastro público — não faz nada com
        // a feature desligada (default); com token ausente/inválido → 403 turnstile_failed.
        turnstileVerifier.verify(request.turnstileToken());
        RegisterMerchantUseCase.Result result = registerMerchant.register(
                new RegisterMerchantUseCase.Command(
                        request.name(), request.email(), request.password(),
                        request.fingerprintHash()));
        Merchant merchant = queryMerchant.findById(result.merchantId())
                .orElseThrow(() -> new MerchantNotFoundException(result.merchantId()));
        // apiKey em texto claro: devolvida UMA unica vez (cadastro) — nunca persistida.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MerchantRegisteredResponse.from(merchant, result.apiKey()));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<MerchantResponse> findById(@PathVariable UUID merchantId) {
        accessGuard.requireMerchantAccess(merchantId);
        Merchant merchant = queryMerchant.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        return ResponseEntity.ok(MerchantResponse.from(merchant));
    }
}
