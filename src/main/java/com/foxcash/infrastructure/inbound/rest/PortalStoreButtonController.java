package com.foxcash.infrastructure.inbound.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.StoreButtonPayloadCodec;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.port.in.StoreButtonManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ReorderButtonsRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreButtonRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreButtonResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador REST: botões do menu da loja no portal (Spec 50),
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista —
 * botão de outro lojista → 404 {@code button_not_found} (sem revelar
 * existência). O vendedor monta o fluxo (tipos + ordem, máx. 20 → 409
 * {@code too_many_buttons}); o bot apenas renderiza (Spec 52).
 */
@RestController
@RequestMapping("/api/v1/portal/store/buttons")
public class PortalStoreButtonController {

    private final StoreButtonManagementUseCase buttons;
    private final StoreButtonPayloadCodec payloadCodec;
    private final ObjectMapper objectMapper;

    public PortalStoreButtonController(StoreButtonManagementUseCase buttons,
                                       StoreButtonPayloadCodec payloadCodec,
                                       ObjectMapper objectMapper) {
        this.buttons = buttons;
        this.payloadCodec = payloadCodec;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<StoreButtonResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return buttons.list(merchantId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public StoreButtonResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return toResponse(buttons.get(merchantId, id));
    }

    @PostMapping
    public ResponseEntity<StoreButtonResponse> create(Authentication authentication,
                                                      @RequestBody StoreButtonRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        StoreButton button = buttons.create(merchantId, toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(button));
    }

    @PatchMapping("/{id}")
    public StoreButtonResponse update(Authentication authentication, @PathVariable UUID id,
                                      @RequestBody StoreButtonRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return toResponse(buttons.update(merchantId, id, toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        buttons.delete(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(Authentication authentication,
                                        @RequestBody ReorderButtonsRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        buttons.reorder(merchantId, request.ids());
        return ResponseEntity.noContent().build();
    }

    private StoreButtonManagementUseCase.SaveButtonCommand toCommand(StoreButtonRequest request) {
        return new StoreButtonManagementUseCase.SaveButtonCommand(
                request.label(), parseAction(request.action()),
                serializePayload(request.payload()), request.active());
    }

    private StoreButtonAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return StoreButtonAction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("action invalida: " + raw);
        }
    }

    /** Serializa o payload Map para JSON (o serviço revalida por tipo). */
    private String serializePayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload invalido");
        }
    }

    private StoreButtonResponse toResponse(StoreButton button) {
        return StoreButtonResponse.from(button, payloadCodec.decode(button.payload()));
    }
}
