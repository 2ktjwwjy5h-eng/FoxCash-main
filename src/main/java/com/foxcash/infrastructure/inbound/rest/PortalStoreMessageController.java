package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.in.StoreMessageManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduleMessageRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduledMessageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.StoreMessageRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreMessageResponse;
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
 * Adaptador REST: mensagens da loja no portal (Spec 51),
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista —
 * mensagem de outro lojista → 404 {@code message_not_found} (sem revelar
 * existência). O vendedor edita os textos (upsert por {@code event}: 1 por
 * etapa) e agenda envios em massa ({@code POST /schedule}); o bot apenas
 * renderiza (Spec 52).
 */
@RestController
@RequestMapping("/api/v1/portal/store/messages")
public class PortalStoreMessageController {

    private final StoreMessageManagementUseCase messages;

    public PortalStoreMessageController(StoreMessageManagementUseCase messages) {
        this.messages = messages;
    }

    @GetMapping
    public List<StoreMessageResponse> list(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return messages.list(merchantId).stream().map(StoreMessageResponse::from).toList();
    }

    @GetMapping("/{id}")
    public StoreMessageResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreMessageResponse.from(messages.get(merchantId, id));
    }

    /** Upsert por event: 201 na criação, 200 na atualização (1 mensagem por etapa). */
    @PostMapping
    public ResponseEntity<StoreMessageResponse> upsert(Authentication authentication,
                                                       @RequestBody StoreMessageRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        StoreMessageManagementUseCase.UpsertResult result =
                messages.upsert(merchantId, toCommand(request));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(StoreMessageResponse.from(result.message()));
    }

    @PatchMapping("/{id}")
    public StoreMessageResponse update(Authentication authentication, @PathVariable UUID id,
                                       @RequestBody StoreMessageRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return StoreMessageResponse.from(messages.update(merchantId, id, toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        messages.delete(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/schedule")
    public ResponseEntity<ScheduledMessageResponse> schedule(Authentication authentication,
                                                             @RequestBody ScheduleMessageRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        ScheduledMessage message = messages.schedule(merchantId,
                new StoreMessageManagementUseCase.ScheduleMessageCommand(
                        request.text(), request.scheduledAt(), request.cron(),
                        ScheduledMessageAudience.parse(request.audience())));
        return ResponseEntity.status(HttpStatus.CREATED).body(ScheduledMessageResponse.from(message));
    }

    private StoreMessageManagementUseCase.SaveMessageCommand toCommand(StoreMessageRequest request) {
        return new StoreMessageManagementUseCase.SaveMessageCommand(
                request.event() == null ? null : StoreMessageEvent.parse(request.event()),
                request.text(), request.active());
    }
}
