package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PlanActionPayloadCodec;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.port.in.PlanManagementUseCase;
import com.foxcash.domain.port.in.PlanManagementUseCase.PlanView;
import com.foxcash.infrastructure.inbound.rest.dto.PlanActionRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanActionResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PlanRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: planos e ações de entrega no portal (Spec 49),
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista —
 * plano de outro lojista → 404 {@code plan_not_found}. A listagem de planos
 * NÃO devolve o payload das ações (só {@code hasDeliveryActions}); o payload
 * sai em claro apenas na API de ações do próprio lojista.
 */
@RestController
@RequestMapping("/api/v1/portal/plans")
public class PortalPlanController {

    private final PlanManagementUseCase plans;
    private final PlanActionPayloadCodec payloadCodec;

    public PortalPlanController(PlanManagementUseCase plans, PlanActionPayloadCodec payloadCodec) {
        this.plans = plans;
        this.payloadCodec = payloadCodec;
    }

    @GetMapping
    public List<PlanResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return plans.list(merchantId).stream().map(PlanResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PlanResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PlanResponse.from(plans.get(merchantId, id));
    }

    @PostMapping
    public ResponseEntity<PlanResponse> create(Authentication authentication,
                                               @RequestBody PlanRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PlanResponse.from(plans.create(merchantId, toPlanCommand(request))));
    }

    @PatchMapping("/{id}")
    public PlanResponse update(Authentication authentication, @PathVariable UUID id,
                               @RequestBody PlanRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return PlanResponse.from(plans.update(merchantId, id, toPlanCommand(request)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        plans.setActive(merchantId, id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        plans.setActive(merchantId, id, false);
        return ResponseEntity.noContent().build();
    }

    // ---------- Ações de entrega ----------

    @GetMapping("/{id}/actions")
    public List<PlanActionResponse> listActions(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return plans.listActions(merchantId, id).stream()
                .map(a -> PlanActionResponse.from(a, payloadCodec.decode(a.payload())))
                .toList();
    }

    @PostMapping("/{id}/actions")
    public ResponseEntity<PlanActionResponse> addAction(Authentication authentication,
                                                        @PathVariable UUID id,
                                                        @RequestBody PlanActionRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        DeliveryAction action = plans.addAction(merchantId, id, toActionCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PlanActionResponse.from(action, payloadCodec.decode(action.payload())));
    }

    @PatchMapping("/actions/{actionId}")
    public PlanActionResponse updateAction(Authentication authentication,
                                           @PathVariable UUID actionId,
                                           @RequestBody PlanActionRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // O planId não vem na URL desta rota (Spec 49: /actions/{actionId}).
        DeliveryAction action = plans.updateAction(merchantId, actionId, toActionCommand(request));
        return PlanActionResponse.from(action, payloadCodec.decode(action.payload()));
    }

    @DeleteMapping("/actions/{actionId}")
    public ResponseEntity<Void> deleteAction(Authentication authentication,
                                             @PathVariable UUID actionId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        plans.deleteAction(merchantId, actionId);
        return ResponseEntity.noContent().build();
    }

    private PlanManagementUseCase.SavePlanCommand toPlanCommand(PlanRequest request) {
        return new PlanManagementUseCase.SavePlanCommand(
                request.name(), request.price(), request.description(),
                request.imageUrl(), request.embedColor(), request.featured());
    }

    private PlanManagementUseCase.SaveActionCommand toActionCommand(PlanActionRequest request) {
        if (request.seq() == null) {
            throw new IllegalArgumentException("seq e obrigatorio na acao de entrega");
        }
        DeliveryActionType type = parseActionType(request.type());
        // Valida as chaves obrigatórias do tipo e serializa em JSON (o serviço
        // persiste o payload criptografado em repouso).
        String payloadJson = payloadCodec.validateAndEncode(type, request.payload());
        return new PlanManagementUseCase.SaveActionCommand(request.seq(), type, payloadJson);
    }

    private DeliveryActionType parseActionType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type e obrigatorio na acao de entrega");
        }
        try {
            return DeliveryActionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type invalido: " + raw);
        }
    }
}
