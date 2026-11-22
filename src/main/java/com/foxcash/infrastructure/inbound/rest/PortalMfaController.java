package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MerchantMfaUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MfaCodeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MfaDeactivateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador REST: gestão do MFA do lojista no portal (Spec 94) — rotas
 * {@code ROLE_MERCHANT_PORTAL} restritas ao próprio lojista (principal =
 * merchantId; o portal não gerencia TOTP de terceiros).
 *
 * <p>Espelho do {@code MasterMfaController} (Spec 44): {@code activate} devolve
 * {@code {otpauthUri, manualEntryKey}} **uma única vez** (o secret nunca mais
 * sai do servidor; em repouso fica criptografado); {@code confirm} valida a
 * posse do app e devolve os 8 recovery codes 1x. Sem step-up (Spec 69 é do
 * master — o lojista exige MFA apenas no login nesta fase). Já ativo →
 * {@code IllegalStateException} → 409 genérico (GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1/portal/mfa")
public class PortalMfaController {

    private final MerchantMfaUseCase mfa;

    public PortalMfaController(MerchantMfaUseCase mfa) {
        this.mfa = mfa;
    }

    /** Passo 1 da ativação: gera o secret e devolve URI + chave manual (1x). */
    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> activate(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        MerchantMfaUseCase.ActivateResult result = mfa.activate(merchantId);
        return ResponseEntity.ok(Map.of(
                "otpauthUri", result.otpauthUri(),
                "manualEntryKey", result.manualEntryKey()));
    }

    /** Passo 2: confirma a posse do app (primeiro TOTP) → ativa + recovery codes. */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            Authentication authentication,
            @Valid @RequestBody MfaCodeRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        MerchantMfaUseCase.ConfirmResult result = mfa.confirm(merchantId, request.code());
        return ResponseEntity.ok(Map.of("recoveryCodes", result.recoveryCodes()));
    }

    /** Desativa: exige senha atual (portal) + TOTP válido (ou recovery) → 204. */
    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivate(
            Authentication authentication,
            @Valid @RequestBody MfaDeactivateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        mfa.deactivate(merchantId, request.code(), request.password());
        return ResponseEntity.noContent().build();
    }

    /** Regenera os recovery codes (requer TOTP válido) — devolve novos 1x. */
    @PostMapping("/recovery-codes")
    public ResponseEntity<Map<String, Object>> regenerateRecoveryCodes(
            Authentication authentication,
            @Valid @RequestBody MfaCodeRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        List<String> codes = mfa.regenerateRecoveryCodes(merchantId, request.code());
        return ResponseEntity.ok(Map.of("recoveryCodes", codes));
    }
}
