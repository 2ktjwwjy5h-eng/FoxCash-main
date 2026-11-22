package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MerchantPortalAuthUseCase;
import com.foxcash.domain.port.in.PasswordResetUseCase;
import com.foxcash.infrastructure.config.AuthProperties;
import com.foxcash.infrastructure.security.TurnstileVerifier;
import com.foxcash.infrastructure.inbound.rest.dto.ForgotPasswordRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ForgotPasswordResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantLoginRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantLoginResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ResetPasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST: autenticacao do lojista no portal (Spec 20) + recuperação de
 * senha (Spec 40).
 *
 * <p>{@code login}/{@code forgot-password}/{@code reset-password} são públicos;
 * {@code logout} exige o token bearer (a revogacao e feita pelo proprio servico,
 * sem depender de autenticacao da rota). O {@code forgot-password} devolve o
 * token apenas quando {@code foxcash.auth.expose-reset-token=true} (dev);
 * em produção a resposta é sempre 204 (anti-enumeração).
 */
@RestController
@RequestMapping("/api/v1/merchant-auth")
public class MerchantAuthController {

    private final MerchantPortalAuthUseCase merchantPortalAuth;
    private final PasswordResetUseCase passwordReset;
    private final AuthProperties authProperties;
    private final TurnstileVerifier turnstileVerifier;

    public MerchantAuthController(MerchantPortalAuthUseCase merchantPortalAuth,
                                  PasswordResetUseCase passwordReset,
                                  AuthProperties authProperties,
                                  TurnstileVerifier turnstileVerifier) {
        this.merchantPortalAuth = merchantPortalAuth;
        this.passwordReset = passwordReset;
        this.authProperties = authProperties;
        this.turnstileVerifier = turnstileVerifier;
    }

    @PostMapping("/login")
    public ResponseEntity<MerchantLoginResponse> login(@Valid @RequestBody MerchantLoginRequest request) {
        // Spec 63 (Fase C): bot mitigation no login público — não faz nada com a
        // feature desligada (default); com token ausente/inválido → 403 turnstile_failed.
        turnstileVerifier.verify(request.turnstileToken());
        MerchantPortalAuthUseCase.LoginResult result = merchantPortalAuth.login(
                new MerchantPortalAuthUseCase.LoginCommand(
                        request.email(), request.password(), request.totpCode(),
                        request.fingerprintHash()));
        return ResponseEntity.ok(MerchantLoginResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String rawToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            rawToken = authorization.substring("Bearer ".length()).trim();
        }
        merchantPortalAuth.logout(rawToken);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        PasswordResetUseCase.ForgotResult result =
                passwordReset.forgot(new PasswordResetUseCase.ForgotCommand(request.email()));
        // Produção (expose-reset-token=false) OU e-mail inexistente: resposta
        // uniforme 204 — sem token em claro e sem vazar se o e-mail existe.
        if (!authProperties.isExposeResetToken() || !result.emailFound()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(new ForgotPasswordResponse(result.resetToken()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(new PasswordResetUseCase.ResetCommand(request.token(), request.newPassword()));
        return ResponseEntity.noContent().build();
    }
}
