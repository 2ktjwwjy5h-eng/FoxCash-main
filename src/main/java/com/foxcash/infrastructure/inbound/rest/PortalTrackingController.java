package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.TrackingProfileCommandService;
import com.foxcash.application.service.TrackingProfileQueryService;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingEventLogResponse;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingProfileRequest;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingSyncResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: perfis de tracking no portal (Spec 81/D7),
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista —
 * perfil de outro lojista → 404 {@code tracking_profile_not_found}. Tokens nunca
 * na resposta (só máscara).
 */
@RestController
@RequestMapping("/api/v1/portal/tracking")
public class PortalTrackingController {

    private final TrackingProfileCommandService commands;
    private final TrackingProfileQueryService queries;
    private final ProfileScopeResolver scopeResolver;

    public PortalTrackingController(TrackingProfileCommandService commands,
                                    TrackingProfileQueryService queries,
                                    ProfileScopeResolver scopeResolver) {
        this.commands = commands;
        this.queries = queries;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping
    public List<TrackingProfileResponse> list(Authentication authentication,
                                              HttpServletRequest httpRequest) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 124 (bot-set): null → todos os perfis do lojista (sem header);
        // perfil ativo → só os com bot do perfil; header inválido → vazio.
        return queries.list(merchantId, scopeResolver.scopeBotIds(merchantId, httpRequest));
    }

    @GetMapping("/{id}")
    public TrackingProfileResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return queries.detail(merchantId, id);
    }

    @PostMapping
    public ResponseEntity<TrackingProfileResponse> create(Authentication authentication,
                                                          @RequestBody TrackingProfileRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        TrackingProfileResponse created = queries.detail(merchantId,
                commands.create(merchantId, request).id());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public TrackingProfileResponse update(Authentication authentication, @PathVariable UUID id,
                                          @RequestBody TrackingProfileRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return queries.detail(merchantId, commands.update(merchantId, id, request).id());
    }

    @PostMapping("/{id}/activate")
    public TrackingProfileResponse activate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return queries.detail(merchantId, commands.activate(merchantId, id).id());
    }

    @PostMapping("/{id}/deactivate")
    public TrackingProfileResponse deactivate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return queries.detail(merchantId, commands.deactivate(merchantId, id).id());
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<TrackingProfileResponse> duplicate(Authentication authentication,
                                                             @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        TrackingProfileResponse copy = queries.detail(merchantId,
                commands.duplicate(merchantId, id).id());
        return ResponseEntity.status(HttpStatus.CREATED).body(copy);
    }

    @PostMapping("/{id}/sync")
    public List<TrackingSyncResult> sync(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return commands.sync(merchantId, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        commands.remove(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/events")
    public PageResponse<TrackingEventLogResponse> events(Authentication authentication,
                                                         @PathVariable UUID id,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PageResponse.from(queries.events(merchantId, id,
                QueryParamParser.clampPage(page), QueryParamParser.clampSize(size)), r -> r);
    }
}
