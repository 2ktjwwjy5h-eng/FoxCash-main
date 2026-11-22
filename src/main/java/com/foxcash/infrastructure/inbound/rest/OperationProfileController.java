package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.OperationProfileService;
import com.foxcash.infrastructure.inbound.rest.dto.CreateProfileRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ProfilesResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReplaceProfileBotsRequest;
import com.foxcash.infrastructure.inbound.rest.dto.UpdateProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Perfis de operação do portal (Spec 122 — fase 1, seletor de contexto por
 * perfil), {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por
 * lojista — perfil de outro lojista → 404 {@code profile_not_found} (IDOR).
 *
 * <p><b>Header {@code X-Profile-ID}:</b> seleção de contexto (qual perfil é o
 * ativo na UI). O backend **valida a pertinência** (perfil não-deletado do
 * lojista autenticado) e usa para definir {@code activeProfileId} no GET —
 * <b>nenhuma lógica de roteamento lê o header</b>: a rota da cobrança vem da
 * linha do perfil no banco ({@code RoutingPolicyResolver.resolveProviders(
 * merchantId, profileId)}). Header ausente/inválido → default (nunca vaza).</p>
 */
@RestController
@RequestMapping("/api/v1/portal/profile")
public class OperationProfileController {

    /** Seleção de contexto (nunca fonte de decisão de rota). */
    public static final String X_PROFILE_ID_HEADER = "X-Profile-ID";

    private final OperationProfileService service;

    public OperationProfileController(OperationProfileService service) {
        this.service = service;
    }

    /** Perfis do lojista + perfil ativo (header validado ou default). */
    @GetMapping
    public ProfilesResponse get(Authentication authentication,
                                @RequestHeader(value = X_PROFILE_ID_HEADER, required = false) String profileId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return service.get(merchantId, parseProfileId(profileId));
    }

    /** Cria um perfil (o primeiro do lojista vira default). Scope opcional (Spec 139). */
    @PostMapping
    public ResponseEntity<ProfileResponse> create(Authentication authentication,
                                                  @RequestBody CreateProfileRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        ProfileResponse created = service.create(merchantId, request.name(), request.gatewayRoute(),
                request.scope());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Atualiza parcialmente um perfil (nome, rota e/ou escopo — Spec 139). */
    @PatchMapping("/{id}")
    public ProfileResponse update(Authentication authentication, @PathVariable UUID id,
                                  @RequestBody UpdateProfileRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return service.update(merchantId, id, request.name(), request.gatewayRoute(), request.scope());
    }

    /** Substitui a frota de bots do perfil (Spec 139 — endpoint novo). */
    @PutMapping("/{id}/bots")
    public ProfileResponse replaceBots(Authentication authentication, @PathVariable UUID id,
                                       @RequestBody ReplaceProfileBotsRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return service.replaceBots(merchantId, id, request.botIds());
    }

    /** Header ausente ou não-UUID → null (o service cai para o default). */
    private static UUID parseProfileId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
