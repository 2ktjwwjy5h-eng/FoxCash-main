package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.port.out.BotLeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link LeadHandoffService} (Spec 90): toggle BOT⇄HUMAN por lead
 * (404 cross-lojista), idempotência e auditoria {@code LEAD_HANDOFF} com
 * before/after na mesma transação.
 */
class LeadHandoffServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID LEAD_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final BotLeadRepository leadRepository = mock(BotLeadRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private LeadHandoffService service;

    @BeforeEach
    void setUp() {
        service = new LeadHandoffService(leadRepository, activityRecorder);
    }

    @Test
    void setHandoffHumanPausesBotAndAudits() {
        BotLead lead = BotLead.restoreCrm(LEAD_ID, BOT_ID, "u-1", NOW, NOW, null,
                null, null, null, null, null, null);
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(lead));

        BotLead updated = service.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN);

        assertThat(updated.handoffStatus()).isEqualTo(LeadHandoffStatus.HUMAN);
        verify(leadRepository).save(any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.LEAD_HANDOFF), eq("LEAD"), eq(LEAD_ID), eq(MERCHANT_ID),
                eq(Map.of("leadId", LEAD_ID.toString(), "before", "BOT", "after", "HUMAN")));
    }

    @Test
    void setHandoffBotResumesAndAudits() {
        BotLead paused = BotLead.restoreCrm(LEAD_ID, BOT_ID, "u-1", NOW, NOW, null,
                null, null, null, null, null, LeadHandoffStatus.HUMAN);
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(paused));

        BotLead updated = service.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.BOT);

        assertThat(updated.handoffStatus()).isEqualTo(LeadHandoffStatus.BOT);
        verify(leadRepository).save(any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.LEAD_HANDOFF), eq("LEAD"), eq(LEAD_ID), eq(MERCHANT_ID),
                eq(Map.of("leadId", LEAD_ID.toString(), "before", "HUMAN", "after", "BOT")));
    }

    @Test
    void repeatedStateIsNoOpWithoutAudit() {
        BotLead lead = BotLead.restoreCrm(LEAD_ID, BOT_ID, "u-1", NOW, NOW, null,
                null, null, null, null, null, LeadHandoffStatus.HUMAN);
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(lead));

        BotLead updated = service.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN);

        assertThat(updated.handoffStatus()).isEqualTo(LeadHandoffStatus.HUMAN);
        verify(leadRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void leadFromAnotherMerchantReturns404() {
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setHandoff(MERCHANT_ID, LEAD_ID, LeadHandoffStatus.HUMAN))
                .isInstanceOf(LeadNotFoundException.class);
        verify(leadRepository, never()).save(any());
    }
}
