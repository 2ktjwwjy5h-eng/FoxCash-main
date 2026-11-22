package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.MasterStepUpService;
import com.foxcash.domain.port.in.MasterMfaUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MfaCodeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MfaDeactivateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador REST: gestão do MFA do master (Spec 44) — rotas {@code ROLE_MASTER}
 * restritas ao próprio perfil (o admin NÃO gerencia TOTP de terceiros — evita
 * escalada admin-sobre-admin).
 *
 * <p>{@code activate} devolve {@code {otpauthUri, manualEntryKey}} **uma única
 * vez** (o secret nunca mais sai do servidor; em repouso fica criptografado);
 * {@code confirm} valida a posse do app e devolve os 8 recovery codes 1x.
 * {@code step-up} (Spec 69) é a re-prova de posse para ações sensíveis — eleva
 * a sessão por 15 min (403 {@code step_up_required} sem ela).
 */
@RestController
@RequestMapping("/api/v1/master/mfa")
public class MasterMfaController {

    private final MasterMfaUseCase mfa;
    private final MasterStepUpService stepUpService;

    public MasterMfaController(MasterMfaUseCase mfa, MasterStepUpService stepUpService) {
        this.mfa = mfa;
        this.stepUpService = stepUpService;
    }

    /** Passo 1 da ativação: gera o secret e devolve URI + chave manual (1x). */
    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> activate(Authentication authentication) {
        UUID masterId = (UUID) authentication.getPrincipal();
        MasterMfaUseCase.ActivateResult result = mfa.activate(masterId);
        return ResponseEntity.ok(Map.of(
                "otpauthUri", result.otpauthUri(),
                "manualEntryKey", result.manualEntryKey()));
    }

    /** Passo 2: confirma a posse do app (primeiro TOTP) → ativa + recovery codes. */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            Authentication authentication,
            @Valid @RequestBody MfaCodeRequest request) {
        UUID masterId = (UUID) authentication.getPrincipal();
        MasterMfaUseCase.ConfirmResult result = mfa.confirm(masterId, request.code());
        return ResponseEntity.ok(Map.of("recoveryCodes", result.recoveryCodes()));
    }

    /** Desativa: exige senha atual + TOTP válido (ou recovery) → 204. */
    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivate(
            Authentication authentication,
            @Valid @RequestBody MfaDeactivateRequest request) {
        UUID masterId = (UUID) authentication.getPrincipal();
        mfa.deactivate(masterId, request.code(), request.password());
        return ResponseEntity.noContent().build();
    }

    /** Regenera os recovery codes (requer TOTP válido) — devolve novos 1x. */
    @PostMapping("/recovery-codes")
    public ResponseEntity<Map<String, Object>> regenerateRecoveryCodes(
            Authentication authentication,
            @Valid @RequestBody MfaCodeRequest request) {
        UUID masterId = (UUID) authentication.getPrincipal();
        List<String> codes = mfa.regenerateRecoveryCodes(masterId, request.code());
        return ResponseEntity.ok(Map.of("recoveryCodes", codes));
    }

    /**
     * Spec 69: step-up MFA — re-prova de posse para ações sensíveis. Valida o
     * 2º fator (TOTP/recovery via {@code verifySecondFactor}) e grava
     * {@code stepped_up_at} na sessão (janela 15 min). Falha → 401/403 do MFA
     * sem gravar step-up; sem step-up as ações sensíveis respondem 403
     * {@code step_up_required}.
     */
    @PostMapping("/step-up")
    public ResponseEntity<Void> stepUp(
            Authentication authentication,
            @Valid @RequestBody MfaCodeRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UUID masterId = (UUID) authentication.getPrincipal();
        stepUpService.stepUp(masterId, request.code(), authorization);
        return ResponseEntity.ok().build();
    }
}
