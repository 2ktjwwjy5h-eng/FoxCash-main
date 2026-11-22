package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PlanActionPayloadCodec;
import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.port.in.DeliveryAccessRecordingUseCase;
import com.foxcash.domain.port.in.DeliveryActionCompletionUseCase;
import com.foxcash.domain.port.in.DeliveryQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryAccessRequest;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryCompleteRequest;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptador REST: entregas de plano (Spec 49) — API do lojista/bot (API key,
 * {@code ROLE_MERCHANT}). O bot busca pendências, recebe o destinatário e
 * executa cada ação confirmando individualmente. Tudo por merchantId do
 * principal (404 cross-loja); replay idempotente (204).
 *
 * <p>Provas de entrega (Spec 49+, 2026-08-06): {@code POST /{id}/access}
 * registra o acesso do comprador ao conteúdo (IP/User-Agent capturados pelo
 * servidor; {@code note} opcional do bot); os logs aparecem em toda resposta
 * de entrega junto com as evidências da compra (termos/IP/dispositivo).
 */
@RestController
@RequestMapping("/api/v1/foxcash/deliveries")
public class FoxDeliveryController {

    private final DeliveryQueryUseCase deliveryQuery;
    private final DeliveryActionCompletionUseCase deliveryCompletion;
    private final DeliveryAccessRecordingUseCase deliveryAccessRecording;
    private final PlanActionPayloadCodec payloadCodec;
    private final ClientIpResolver clientIpResolver;

    public FoxDeliveryController(DeliveryQueryUseCase deliveryQuery,
                                 DeliveryActionCompletionUseCase deliveryCompletion,
                                 DeliveryAccessRecordingUseCase deliveryAccessRecording,
                                 PlanActionPayloadCodec payloadCodec,
                                 ClientIpResolver clientIpResolver) {
        this.deliveryQuery = deliveryQuery;
        this.deliveryCompletion = deliveryCompletion;
        this.deliveryAccessRecording = deliveryAccessRecording;
        this.payloadCodec = payloadCodec;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/pending")
    public List<DeliveryResponse> pending(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return deliveryQuery.pending(merchantId).stream()
                .map(delivery -> responseOf(merchantId, delivery))
                .toList();
    }

    @GetMapping("/by-transaction/{transactionId}")
    public DeliveryResponse byTransaction(Authentication authentication,
                                          @PathVariable UUID transactionId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return responseOf(merchantId, deliveryQuery.byTransaction(merchantId, transactionId));
    }

    @PostMapping("/{id}/actions/{seq}/complete")
    public ResponseEntity<Void> complete(Authentication authentication,
                                         @PathVariable UUID id,
                                         @PathVariable int seq,
                                         @RequestBody DeliveryCompleteRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        if (request.status() == null) {
            throw new IllegalArgumentException("status e obrigatorio (DONE|FAILED)");
        }
        deliveryCompletion.complete(merchantId, id, seq, request.status(), request.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public DeliveryResponse retry(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return responseOf(merchantId, deliveryCompletion.retry(merchantId, id));
    }

    /**
     * Registra o acesso do comprador ao conteúdo entregue (prova de entrega,
     * Spec 49+): o bot confirma que o comprador usou o produto. IP e User-Agent
     * são capturados do request; {@code note} opcional (detalhe livre).
     */
    @PostMapping("/{id}/access")
    public ResponseEntity<Void> recordAccess(Authentication authentication,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody(required = false) DeliveryAccessRequest request,
                                             HttpServletRequest httpRequest) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        String userAgent = httpRequest.getHeader("User-Agent");
        String device = (userAgent != null && userAgent.length() > 255)
                ? userAgent.substring(0, 255) : userAgent;
        deliveryAccessRecording.record(merchantId, id,
                clientIpResolver.resolve(httpRequest), device,
                request == null ? null : request.note());
        return ResponseEntity.noContent().build();
    }

    /** Monta a resposta com as ações da entrega + payloads decifrados (dono) + provas. */
    private DeliveryResponse responseOf(UUID merchantId, PlanDelivery delivery) {
        List<DeliveryActionExec> execs = deliveryQuery.actions(merchantId, delivery.id());
        List<DeliveryAccessLog> accessLogs = deliveryQuery.accessLogs(merchantId, delivery.id());
        Map<UUID, Map<String, Object>> payloads = new LinkedHashMap<>();
        for (DeliveryActionExec exec : execs) {
            payloads.put(exec.id(), payloadCodec.decode(exec.payloadSnapshot()));
        }
        return DeliveryResponse.of(delivery, execs, payloads, accessLogs);
    }
}
