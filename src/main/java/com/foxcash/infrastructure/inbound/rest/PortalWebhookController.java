package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.WebhookManagementService;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookCreateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookCreatedResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookSampleResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookTestRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookTestResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: gestao de webhooks de saida do lojista no portal (Spec 35),
 * role {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por
 * lojista — webhook de outro lojista → 404 sem revelar existencia.
 *
 * <p>Spec 119 (aditivo): a listagem devolve {@code status}/{@code attempts}/
 * {@code lastError} (última falha de entrega do lojista — do outbox) e o
 * endpoint {@code GET /{id}/sample?type=} expõe um exemplo do payload do
 * evento (mock do contrato, SEM PII).
 */
@RestController
@RequestMapping("/api/v1/portal/webhooks")
public class PortalWebhookController {

    private final WebhookManagementService webhooks;
    private final NotificationOutboxPort outbox;

    public PortalWebhookController(WebhookManagementService webhooks,
                                   NotificationOutboxPort outbox) {
        this.webhooks = webhooks;
        this.outbox = outbox;
    }

    @GetMapping
    public List<WebhookResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 119: última falha de entrega do lojista (outbox) — exposta como
        // attempts/lastError em TODOS os webhooks (dados reais da Spec 35; o
        // outbox não referencia o webhook id — a falha é por entrega do lojista).
        OutboxNotification latestError = outbox.findLatestDeliveryError(merchantId).orElse(null);
        int attempts = latestError != null ? latestError.attempts() : 0;
        String lastError = latestError != null ? latestError.lastError() : null;
        return webhooks.list(merchantId).stream()
                .map(hook -> WebhookResponse.from(hook, attempts, lastError))
                .toList();
    }

    @PostMapping
    public ResponseEntity<WebhookCreatedResponse> create(Authentication authentication,
                                                         @Valid @RequestBody WebhookCreateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        WebhookManagementService.CreatedWebhook created =
                webhooks.create(merchantId, request.url(), request.events());
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookCreatedResponse.from(created));
    }

    @PatchMapping("/{id}")
    public WebhookResponse update(Authentication authentication,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody WebhookUpdateRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return WebhookResponse.from(webhooks.update(merchantId, id, request.url(), request.events(),
                request.signatureVersion()));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        webhooks.setActive(merchantId, id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        webhooks.setActive(merchantId, id, false);
        return ResponseEntity.noContent().build();
    }

    /** Exemplo do payload do evento (Spec 119 — mock do contrato, SEM PII). */
    @GetMapping("/{id}/sample")
    public WebhookSampleResponse sample(Authentication authentication,
                                        @PathVariable UUID id,
                                        @RequestParam(required = false) String type) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // 404 cross-lojista (o webhook precisa ser do lojista autenticado).
        webhooks.list(merchantId).stream()
                .filter(hook -> hook.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new com.foxcash.domain.exception.WebhookNotFoundException(id));
        return WebhookSampleResponse.forType(type);
    }

    @PostMapping("/test")
    public WebhookTestResponse test(Authentication authentication,
                                    @Valid @RequestBody WebhookTestRequest request) {
        // Auditoria 2026-08-05 (B-05): o teste passa a ser por conta do lojista —
        // apenas URLs de webhooks cadastrados dele podem ser testadas (o servidor
        // não vira relay de requisições a hosts arbitrários públicos).
        UUID merchantId = (UUID) authentication.getPrincipal();
        return WebhookTestResponse.from(
                webhooks.test(merchantId, request.url(), request.secret(), request.eventType()));
    }
}
