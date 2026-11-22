package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.ChatEventPublisher;
import com.foxcash.application.service.CrmLeadService;
import com.foxcash.application.service.LeadHandoffService;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: CRM do portal (Spec 83) + handoff humano (Spec 90) —
 * {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Privado por lojista
 * (404 cross-lojista). Endpoints: lista/busca de leads, detalhe (com
 * {@code handoffStatus}), histórico e envio de mensagens, marcar lida, resumo
 * (cards), alternar handoff e o stream SSE do chat.
 */
@RestController
@RequestMapping("/api/v1/portal/crm")
public class PortalCrmController {

    private final CrmLeadService crmLeadService;
    private final LeadHandoffService handoffService;
    private final ChatEventPublisher eventPublisher;
    private final ProfileScopeResolver scopeResolver;

    public PortalCrmController(CrmLeadService crmLeadService,
                               LeadHandoffService handoffService,
                               ChatEventPublisher eventPublisher,
                               ProfileScopeResolver scopeResolver) {
        this.crmLeadService = crmLeadService;
        this.handoffService = handoffService;
        this.eventPublisher = eventPublisher;
        this.scopeResolver = scopeResolver;
    }

    /** Lista paginada de leads (Spec 83 C3) — busca q + filtros. */
    @GetMapping("/leads")
    public PageResponse<CrmLeadResponse> listLeads(Authentication authentication,
                                                   HttpServletRequest httpRequest,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) UUID botId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Instant from,
                                                   @RequestParam(required = false) Instant to,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 124 (filtro rígido por bot-set): null → lojista inteiro (sem
        // header); perfil ativo → só bots do perfil; header inválido → vazio.
        PageResult<BotLead> result = crmLeadService.search(merchantId,
                scopeResolver.scopeBotIds(merchantId, httpRequest), q, botId,
                parseStatus(status), from, to,
                QueryParamParser.clampPage(page), QueryParamParser.clampSize(size));
        return PageResponse.from(result, lead -> {
            long unread = crmLeadService.unreadCount(merchantId, lead.id());
            // Spec 119: colunas aditivas (última interação + plano da última
            // compra); flowName = null (funil de automações ainda não existe).
            var enrichment = crmLeadService.enrich(merchantId, lead.id()).orElse(null);
            return CrmLeadResponse.from(lead, unread, enrichment);
        });
    }

    /** Detalhe do lead (Spec 83 C3 + Spec 90 C6 — inclui {@code handoffStatus}). */
    @GetMapping("/leads/{id}")
    public CrmLeadResponse getLead(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return CrmLeadResponse.from(crmLeadService.get(merchantId, id),
                crmLeadService.unreadCount(merchantId, id));
    }

    /** Histórico paginado do chat (Spec 83 D9). */
    @GetMapping("/leads/{id}/messages")
    public PageResponse<ChatMessageResponse> messages(Authentication authentication,
                                                      @PathVariable UUID id,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        PageResult<ChatMessage> result = crmLeadService.history(merchantId, id,
                QueryParamParser.clampPage(page), QueryParamParser.clampSize(size));
        return PageResponse.from(result, ChatMessageResponse::from);
    }

    /** Envia mensagem do painel (Spec 83 D5) — 409 lead_blocked / 502 falha. */
    @PostMapping("/leads/{id}/messages")
    public ChatMessageResponse sendMessage(Authentication authentication,
                                           @PathVariable UUID id,
                                           @RequestBody SendMessageRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        ChatMessage sent = crmLeadService.sendMessage(merchantId, id,
                request.text(), request.mediaUrl());
        return ChatMessageResponse.from(sent);
    }

    /** Marca as mensagens CLIENT como lidas (Spec 83 C11). */
    @PostMapping("/leads/{id}/read")
    public ResponseEntity<Void> markRead(Authentication authentication, @PathVariable UUID id) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        crmLeadService.markRead(merchantId, id);
        return ResponseEntity.noContent().build();
    }

    /** Alterna o atendimento do lead BOT ⇄ HUMAN (Spec 90 C6). */
    @PutMapping("/leads/{id}/handoff")
    public CrmLeadResponse setHandoff(Authentication authentication,
                                      @PathVariable UUID id,
                                      @RequestBody HandoffRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        if (request.mode() == null) {
            throw new IllegalArgumentException("mode e obrigatorio (BOT | HUMAN)");
        }
        BotLead updated = handoffService.setHandoff(merchantId, id,
                LeadHandoffStatus.valueOf(request.mode().trim().toUpperCase()));
        return CrmLeadResponse.from(updated, crmLeadService.unreadCount(merchantId, id));
    }

    /** Resumo dos cards (Spec 83 C4). */
    @GetMapping("/summary")
    public CrmLeadService.CrmSummary summary(Authentication authentication,
                                             HttpServletRequest httpRequest,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return crmLeadService.summary(merchantId,
                scopeResolver.scopeBotIds(merchantId, httpRequest), from, to);
    }

    /** Stream SSE do chat (Spec 83 C8) — eventos {@code {type, leadId, message}}. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication,
                             @RequestParam(value = "Last-Event-ID", required = false) String lastEventId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return eventPublisher.connect(merchantId, lastEventId);
    }

    private static CrmLeadStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CrmLeadStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status invalido: " + raw);
        }
    }

    /** Response do lead do CRM (Spec 83/90/119 — inclui handoffStatus, não lidas
     *  e as colunas aditivas do Spec 119: plano/última interação/fluxo). */
    public record CrmLeadResponse(
            UUID id,
            UUID botId,
            String platformUserId,
            String name,
            String username,
            String email,
            String phone,
            String status,
            String handoffStatus,
            Instant firstSeenAt,
            Instant createdAt,
            long unreadCount,
            String lastPlanName,
            Instant lastInteractionAt,
            String flowName) {

        static CrmLeadResponse from(BotLead lead, long unreadCount) {
            return from(lead, unreadCount, null);
        }

        static CrmLeadResponse from(BotLead lead, long unreadCount,
                                    com.foxcash.domain.port.out.BotLeadRepository.LeadEnrichment enrichment) {
            return new CrmLeadResponse(lead.id(), lead.botId(), lead.platformUserId(),
                    lead.name(), lead.username(), lead.email(), lead.phone(),
                    lead.status().name(), lead.handoffStatus().name(),
                    lead.firstSeenAt(), lead.createdAt(), unreadCount,
                    enrichment != null ? enrichment.lastPlanName() : null,
                    enrichment != null ? enrichment.lastInteractionAt() : null,
                    null);
        }
    }

    /** Response de mensagem do chat. */
    public record ChatMessageResponse(
            UUID id,
            UUID leadId,
            String senderType,
            String textContent,
            String mediaUrl,
            boolean read,
            Instant createdAt) {

        static ChatMessageResponse from(ChatMessage message) {
            return new ChatMessageResponse(message.id(), message.leadId(),
                    message.senderType().name(), message.textContent(), message.mediaUrl(),
                    message.read(), message.createdAt());
        }
    }

    /** Body do envio de mensagem (Spec 83 D5 — texto OU mídia). */
    public record SendMessageRequest(String text, String mediaUrl) {
    }

    /** Body do handoff (Spec 90 C6 — {@code mode: BOT | HUMAN}). */
    public record HandoffRequest(String mode) {
    }
}
