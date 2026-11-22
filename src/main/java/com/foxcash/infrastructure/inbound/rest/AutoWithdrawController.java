package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.AutoWithdrawConfigService;
import com.foxcash.infrastructure.inbound.rest.dto.AutoWithdrawConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.AutoWithdrawConfigResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: config de saque automatico por limiar de saldo (Spec 70),
 * role {@code ROLE_MERCHANT_PORTAL} (principal = merchantId — nunca do body;
 * impossivel IDOR, mesmo padrao do {@link PortalWalletController}).
 *
 * <p>{@code GET} nunca devolve 404 (config ausente → defaults da plataforma);
 * {@code PUT} faz upsert e responde 200 com o recurso atualizado. Regras de
 * dominio (threshold, destino) validam no servico — 400 {@code
 * invalid_argument}/404 {@code destination_not_found}/409
 * {@code destination_inactive} via {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/portal/auto-withdraw")
public class AutoWithdrawController {

    private final AutoWithdrawConfigService service;

    public AutoWithdrawController(AutoWithdrawConfigService service) {
        this.service = service;
    }

    @GetMapping
    public AutoWithdrawConfigResponse get(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return AutoWithdrawConfigResponse.from(service.get(merchantId));
    }

    @PutMapping
    public AutoWithdrawConfigResponse update(Authentication authentication,
                                             @Valid @RequestBody AutoWithdrawConfigRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return AutoWithdrawConfigResponse.from(service.update(merchantId,
                request.enabled(), request.threshold(), request.destinationId()));
    }
}
