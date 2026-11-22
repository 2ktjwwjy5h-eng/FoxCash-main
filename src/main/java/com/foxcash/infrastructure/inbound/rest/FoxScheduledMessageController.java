package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.ScheduledMessageQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduledMessageDueResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: mensagens programadas (Spec 51) — API do lojista/bot (API
 * key, {@code ROLE_MERCHANT}). O bot busca as vencidas
 * ({@code GET /api/v1/foxcash/scheduled-messages?dueBefore=<ISO>}) com os
 * {@code recipients} (platform_user_id dos compradores com entrega entregue da
 * loja — Spec 49) e confirma o envio ({@code POST /{id}/sent} → 204,
 * idempotente). 404 cross-loja sem revelar existência.
 */
@RestController
@RequestMapping("/api/v1/foxcash/scheduled-messages")
public class FoxScheduledMessageController {

    private final ScheduledMessageQueryUseCase scheduledMessages;

    public FoxScheduledMessageController(ScheduledMessageQueryUseCase scheduledMessages) {
        this.scheduledMessages = scheduledMessages;
    }

    @GetMapping
    public List<ScheduledMessageDueResponse> due(Authentication authentication,
                                                 @RequestParam("dueBefore") String dueBefore) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return scheduledMessages.due(merchantId, parseInstant(dueBefore)).stream()
                .map(due -> ScheduledMessageDueResponse.from(due.message(), due.recipients()))
                .toList();
    }

    @PostMapping("/{id}/sent")
    public ResponseEntity<Void> markSent(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        scheduledMessages.markSent(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    private Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("dueBefore deve ser um timestamp ISO-8601 valido");
        }
    }
}
