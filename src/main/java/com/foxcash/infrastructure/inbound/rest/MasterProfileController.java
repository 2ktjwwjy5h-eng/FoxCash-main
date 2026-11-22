package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MasterProfileUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterCreatedProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MasterProfileRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MasterProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: gestão de perfis master (Spec 41). **Rota não-documentada
 * publicamente** — a autorização é por flag {@code can_manage_masters}
 * (verificada no serviço): apenas o admin cria/lista/ativa/desativa perfis;
 * sem a flag → 403 {@code forbidden} (a defesa real é autorização + auditoria,
 * não o path).
 */
@RestController
@RequestMapping("/api/v1/master/profiles")
public class MasterProfileController {

    private final MasterProfileUseCase profileUseCase;

    public MasterProfileController(MasterProfileUseCase profileUseCase) {
        this.profileUseCase = profileUseCase;
    }

    @PostMapping
    public ResponseEntity<MasterCreatedProfileResponse> create(@Valid @RequestBody MasterProfileRequest request,
                                                               Authentication authentication) {
        UUID actorMasterId = (UUID) authentication.getPrincipal();
        MasterProfileUseCase.CreatedProfile created = profileUseCase.create(
                new MasterProfileUseCase.CreateCommand(request.username(), request.name()), actorMasterId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MasterCreatedProfileResponse.from(MasterProfileResponse.from(created.profile()),
                        created.temporaryPassword()));
    }

    @GetMapping
    public List<MasterProfileResponse> list(Authentication authentication) {
        UUID actorMasterId = (UUID) authentication.getPrincipal();
        return profileUseCase.list(actorMasterId).stream()
                .map(MasterProfileResponse::from).toList();
    }

    @PostMapping("/{profileId}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID profileId, Authentication authentication) {
        profileUseCase.activate(profileId, (UUID) authentication.getPrincipal());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{profileId}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID profileId, Authentication authentication) {
        profileUseCase.deactivate(profileId, (UUID) authentication.getPrincipal());
        return ResponseEntity.noContent().build();
    }
}
