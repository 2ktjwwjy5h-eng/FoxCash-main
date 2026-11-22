package com.foxcash.infrastructure.inbound.webhook;

import com.foxcash.domain.port.in.ProcessPixWebhookUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PixWebhookRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada: recebe callbacks do provedor Pix (PSP).
 */
@RestController
@RequestMapping("/webhooks/pix")
public class PixWebhookController {

    private final ProcessPixWebhookUseCase processPixWebhook;

    public PixWebhookController(ProcessPixWebhookUseCase processPixWebhook) {
        this.processPixWebhook = processPixWebhook;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody PixWebhookRequest request) {
        processPixWebhook.process(
                new ProcessPixWebhookUseCase.WebhookEvent(request.externalReference(), request.status(),
                        request.amount()));
        return ResponseEntity.noContent().build();
    }
}
