package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MasterProfileUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterMeResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: perfil do master autenticado (Spec 71, fatia 1) — base da UI
 * RBAC-aware do Master Console. Rota de LEITURA, sem guard de permissao: todo
 * master autenticado ve o proprio perfil (o guard protege acoes, nao a
 * auto-leitura). O {@code masterId} vem do principal do {@link
 * com.foxcash.infrastructure.security.MasterAuthFilter} — nunca do body/query
 * (sem IDOR).
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterMeController {

    private final MasterProfileUseCase profileUseCase;

    public MasterMeController(MasterProfileUseCase profileUseCase) {
        this.profileUseCase = profileUseCase;
    }

    @GetMapping("/me")
    public MasterMeResponse me(Authentication authentication) {
        UUID masterId = (UUID) authentication.getPrincipal();
        return MasterMeResponse.from(profileUseCase.selfProfile(masterId));
    }
}
