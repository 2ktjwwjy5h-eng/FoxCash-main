package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.port.in.ApiKeyManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ApiKeyResponse;
import com.foxcash.infrastructure.inbound.rest.dto.CreateApiKeyRequest;
import com.foxcash.infrastructure.inbound.rest.dto.CreateApiKeyResponse;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Adaptador REST: credenciais de API do lojista no portal (Spec 24),
 * {@code ROLE_MERCHANT_PORTAL}. Lista chaves (sem o segredo), cria nova
 * (segredo devolvido UMA vez) e revoga. O merchantId vem do principal da
 * sessao — nunca do body.
 */
@RestController
@RequestMapping("/api/v1/portal/credentials")
public class PortalCredentialsController {

    private final ApiKeyManagementUseCase apiKeyManagement;

    public PortalCredentialsController(ApiKeyManagementUseCase apiKeyManagement) {
        this.apiKeyManagement = apiKeyManagement;
    }

    @GetMapping
    public List<ApiKeyResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return ApiKeyResponse.from(apiKeyManagement.list(merchantId));
    }

    @PostMapping
    public ResponseEntity<CreateApiKeyResponse> create(Authentication authentication,
                                                       @Valid @RequestBody CreateApiKeyRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        ApiKeyManagementUseCase.CreateResult result = apiKeyManagement.create(merchantId,
                new ApiKeyManagementUseCase.CreateCommand(request.label(), parseScopes(request.scopes())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateApiKeyResponse(result.keyId(), result.secret()));
    }

    @PostMapping("/{keyId}/revoke")
    public ResponseEntity<Void> revoke(Authentication authentication, @PathVariable UUID keyId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        apiKeyManagement.revoke(merchantId, keyId);
        return ResponseEntity.noContent().build();
    }

    /** Escopos ausentes = escopo total; escopo desconhecido → 400. */
    private Set<ApiKeyScope> parseScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        Set<ApiKeyScope> parsed = new LinkedHashSet<>();
        for (String scope : scopes) {
            ApiKeyScope apiKeyScope = ApiKeyScope.from(scope);
            if (apiKeyScope == null) {
                throw new IllegalArgumentException("Escopo invalido: " + scope);
            }
            parsed.add(apiKeyScope);
        }
        return parsed;
    }
}
