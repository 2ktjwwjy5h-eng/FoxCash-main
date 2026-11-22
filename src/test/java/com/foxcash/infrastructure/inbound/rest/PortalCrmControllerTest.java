package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.ChatEventPublisher;
import com.foxcash.application.service.CrmLeadService;
import com.foxcash.application.service.LeadHandoffService;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.infrastructure.inbound.rest.PortalCrmController.CrmLeadResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do CRM do portal (Specs 83/90): o merchantId vem do
 * principal (ROLE_MERCHANT_PORTAL), GET inclui {@code handoffStatus}, PUT
 * handoff alterna (200/400/404 cross-lojista), envio persiste ADMIN e o SSE é
 * servido com o emitter do publisher.
 */
class PortalCrmControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID LEAD_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private final CrmLeadService crmLeadService = mock(CrmLeadService.class);
    private final LeadHandoffService handoffService = mock(LeadHandoffService.class);
    private final ChatEventPublisher eventPublisher = mock(ChatEventPublisher.class);
    private final ProfileScopeResolver scopeResolver = mock(ProfileScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalCrmController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalCrmController(crmLeadService, handoffService, eventPublisher, scopeResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        // Sem header → botIds null (lojista inteiro — regressão Spec 124).
        when(scopeResolver.scopeBotIds(any(), any())).thenReturn(null);
    }

    private BotLead lead(LeadHandoffStatus handoff) {
        return BotLead.restoreCrm(LEAD_ID, BOT_ID, "u-1", NOW, NOW, null,
                "Cliente E2E", "user1", null, null, CrmLeadStatus.NOVO, handoff);
    }

    @Test
    void getLeadIncludesHandoffStatus() {
        when(crmLeadService.get(MERCHANT_ID, LEAD_ID)).thenReturn(lead(LeadHandoffStatus.HUMAN));
        when(crmLeadService.unreadCount(MERCHANT_ID, LEAD_ID)).thenReturn(3L);

        CrmLeadResponse response = controller.getLead(authentication, LEAD_ID);

        assertThat(response.handoffStatus()).isEqualTo("HUMAN");
        assertThat(response.id()).isEqualTo(LEAD_ID);
        assertThat(response.unreadCount()).isEqualTo(3L);
    }

    @Test
    void setHandoffHumanReturnsUpdatedLead() {
        when(handoffService.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN))
                .thenReturn(lead(LeadHandoffStatus.HUMAN));
        when(crmLeadService.unreadCount(MERCHANT_ID, LEAD_ID)).thenReturn(0L);

        CrmLeadResponse response = controller.setHandoff(authentication, LEAD_ID,
                new PortalCrmController.HandoffRequest("HUMAN"));

        assertThat(response.handoffStatus()).isEqualTo("HUMAN");
        verify(handoffService).setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN);
    }

    @Test
    void setHandoffWithInvalidModeThrows400() {
        assertThatThrownBy(() -> controller.setHandoff(authentication, LEAD_ID,
                new PortalCrmController.HandoffRequest("qualquer-coisa")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setHandoffMissingModeThrows400() {
        assertThatThrownBy(() -> controller.setHandoff(authentication, LEAD_ID,
                new PortalCrmController.HandoffRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossMerchantLeadPropagates404() {
        when(handoffService.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN))
                .thenThrow(new LeadNotFoundException("Lead nao encontrado"));

        assertThatThrownBy(() -> controller.setHandoff(authentication, LEAD_ID,
                new PortalCrmController.HandoffRequest("HUMAN")))
                .isInstanceOf(LeadNotFoundException.class);
    }

    @Test
    void listLeadsReturnsPageWithHandoff() {
        // Sem header → botIds null (lojista inteiro — regressão Spec 124).
        when(crmLeadService.search(eq(MERCHANT_ID), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(lead(LeadHandoffStatus.BOT)), 0, 20, 1));
        when(crmLeadService.unreadCount(MERCHANT_ID, LEAD_ID)).thenReturn(0L);

        var response = controller.listLeads(authentication, new MockHttpServletRequest(),
                null, null, null, null, null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).handoffStatus()).isEqualTo("BOT");
    }

    @Test
    void sendMessageDelegatesToService() {
        ChatMessage message = ChatMessage.outbound(LEAD_ID, BOT_ID, "oi", null, NOW);
        when(crmLeadService.sendMessage(MERCHANT_ID, LEAD_ID, "oi", null)).thenReturn(message);

        var response = controller.sendMessage(authentication, LEAD_ID,
                new PortalCrmController.SendMessageRequest("oi", null));

        assertThat(response.senderType()).isEqualTo("ADMIN");
        assertThat(response.textContent()).isEqualTo("oi");
    }

    @Test
    void streamReturnsEmitterFromPublisher() {
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(eventPublisher.connect(MERCHANT_ID, null)).thenReturn(emitter);

        assertThat(controller.stream(authentication, null)).isSameAs(emitter);
    }
}
