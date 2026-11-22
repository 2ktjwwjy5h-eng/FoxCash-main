package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.StoreManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.StoreRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: loja do vendedor no portal (Spec 48),
 * {@code ROLE_MERCHANT_PORTAL}. CRUD da loja do principal (GET/POST/PATCH) —
 * o merchantId vem do principal da sessao, nunca do body. 1 loja por lojista:
 * segundo {@code POST} → 409 {@code store_already_exists}; sem loja → 404
 * {@code store_not_found}.
 */
@RestController
@RequestMapping("/api/v1/portal/store")
public class PortalStoreController {

    private final StoreManagementUseCase storeManagement;

    public PortalStoreController(StoreManagementUseCase storeManagement) {
        this.storeManagement = storeManagement;
    }

    @GetMapping
    public StoreResponse get(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreResponse.from(storeManagement.get(merchantId));
    }

    @PostMapping
    public ResponseEntity<StoreResponse> create(Authentication authentication,
                                                @RequestBody StoreRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Store store = storeManagement.create(merchantId, toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(StoreResponse.from(store));
    }

    @PatchMapping
    public ResponseEntity<StoreResponse> update(Authentication authentication,
                                                @RequestBody StoreRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Store store = storeManagement.update(merchantId, toCommand(request));
        return ResponseEntity.ok(StoreResponse.from(store));
    }

    private StoreManagementUseCase.SaveStoreCommand toCommand(StoreRequest request) {
        return new StoreManagementUseCase.SaveStoreCommand(
                request.name(),
                request.logoUrl(),
                request.bannerUrl(),
                request.description(),
                request.toSocialLinks(),
                request.color(),
                request.emoji(),
                request.title(),
                request.footer());
    }
}
