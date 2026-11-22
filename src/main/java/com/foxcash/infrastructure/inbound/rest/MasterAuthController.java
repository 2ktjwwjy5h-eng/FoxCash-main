package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MasterAuthUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterChangePasswordRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MasterLoginRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MasterLoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: autenticacao do perfil master (Spec 13).
 *
 * <p>{@code POST /api/v1/auth/master/login} e publico; {@code logout} exige o
 * token bearer (a revogacao e feita pelo proprio servico, sem depender de
 * autenticacao da rota). {@code change-password} (SEC-25) exige {@code ROLE_MASTER}
 * (SecurityConfig) — o principal é o id do master autenticado.
 */
@RestController
@RequestMapping("/api/v1/auth/master")
public class MasterAuthController {

    private final MasterAuthUseCase masterAuth;

    public MasterAuthController(MasterAuthUseCase masterAuth) {
        this.masterAuth = masterAuth;
    }

    @PostMapping("/login")
    public ResponseEntity<MasterLoginResponse> login(@Valid @RequestBody MasterLoginRequest request) {
        MasterAuthUseCase.LoginResult result = masterAuth.login(
                new MasterAuthUseCase.LoginCommand(request.username(), request.password(), request.totpCode()));
        return ResponseEntity.ok(MasterLoginResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String rawToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            rawToken = authorization.substring("Bearer ".length()).trim();
        }
        masterAuth.logout(rawToken);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * SEC-25 (revisão de segurança 2026-08-11): troca a senha do master
     * autenticado — verifica a senha atual (BCrypt, timing uniforme) e revoga
     * TODAS as sessões do master (re-login obrigatório; tokens roubados morrem
     * no instante da troca).
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody MasterChangePasswordRequest request,
                                               Authentication authentication) {
        UUID masterId = (UUID) authentication.getPrincipal();
        masterAuth.changePassword(masterId, request.currentPassword(), request.newPassword());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
