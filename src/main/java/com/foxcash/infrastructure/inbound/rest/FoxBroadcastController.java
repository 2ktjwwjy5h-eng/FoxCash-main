package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.BroadcastDispatchUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastDueResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastLogRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WelcomeTriggerRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: API do bot para broadcasts (Spec 79) — API key do lojista
 * (principal = {@code merchantId}), padrão Spec 51. O bot busca as pendências
 * ({@code GET /due?dueBefore=}), confirma o envio ({@code POST /{id}/sent} →
 * 204, idempotente), reporta o log por destinatário ({@code POST /{id}/logs},
 * idempotente por {@code (broadcast_id, recipient_id)}) e resolve o evento de
 * entrada (WELCOME — {@code POST /welcome-trigger} → 200 com a mensagem ou
 * 204 no-op). 404 cross-loja sem revelar existência.
 */
@RestController
@RequestMapping("/api/v1/foxcash/broadcasts")
public class FoxBroadcastController {

    private final BroadcastDispatchUseCase dispatch;

    public FoxBroadcastController(BroadcastDispatchUseCase dispatch) {
        this.dispatch = dispatch;
    }

    @GetMapping("/due")
    public List<BroadcastDueResponse> due(Authentication authentication,
                                          @RequestParam("dueBefore") String dueBefore) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return dispatch.due(merchantId, parseInstant(dueBefore)).stream()
                .map(BroadcastDueResponse::from).toList();
    }

    @PostMapping("/{id}/sent")
    public ResponseEntity<Void> markSent(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        dispatch.markSent(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/logs")
    public ResponseEntity<Void> reportLog(Authentication authentication, @PathVariable UUID id,
                                          @RequestBody BroadcastLogRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        dispatch.reportLog(merchantId, id, request.recipientId(), request.parsedStatus(),
                request.errorMessage());
        return ResponseEntity.noContent().build();
    }

    /**
     * Evento de entrada de membro (D6): 200 com a mensagem WELCOME ativa do chat
     * + mídia/botões (o bot envia); 204 (no-op) quando não há mensagem ou o
     * usuário já foi atendido (idempotência por {@code (chatId, userId)}).
     */
    @PostMapping("/welcome-trigger")
    public ResponseEntity<?> welcomeTrigger(Authentication authentication,
                                            @RequestBody WelcomeTriggerRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        BroadcastDispatchUseCase.WelcomeResult result =
                dispatch.welcomeTrigger(merchantId, request.botId(),
                        request.chatId(), request.userId());
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    private Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("dueBefore deve ser um timestamp ISO-8601 valido");
        }
    }
}
