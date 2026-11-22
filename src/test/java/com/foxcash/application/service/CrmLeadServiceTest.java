package com.foxcash.application.service;

import com.foxcash.application.service.ChatEventPublisher.ChatEvent;
import com.foxcash.domain.exception.LeadBlockedException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.ChatMessage;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.ChatMessageRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link CrmLeadService} (Spec 83) — envio do painel (Telegram),
 * persistência de mensagem recebida e as métricas {@code foxcash_crm_*} (C12:
 * mensagens enviadas/recebidas e bloqueios — padrão Spec 53).
 */
class CrmLeadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID LEAD_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final BotLeadRepository leadRepository = mock(BotLeadRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatEventPublisher eventPublisher = mock(ChatEventPublisher.class);
    private final TelegramBotApi telegramApi = mock(TelegramBotApi.class);
    private final LeadStatusDerivationService statusDerivation = mock(LeadStatusDerivationService.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private CrmLeadService service;

    @BeforeEach
    void setUp() {
        service = new CrmLeadService(leadRepository, botRepository, messageRepository,
                eventPublisher, telegramApi, statusDerivation, activityRecorder,
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private BotLead lead(CrmLeadStatus status) {
        return BotLead.restoreCrm(LEAD_ID, BOT_ID, "u-1", NOW, NOW, null,
                "Cliente", "cliente", null, null, status, LeadHandoffStatus.BOT);
    }

    private StoreBot telegramBot() {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "token",
                "tokenHash", "webhookHash", "Bot", "botuser", null, BotStatus.ACTIVE,
                null, null, null, NOW, NOW);
    }

    @Test
    void sendMessagePersistsAdminAndIncrementsSentMetric() {
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(lead(CrmLeadStatus.NOVO)));
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID)).thenReturn(Optional.of(telegramBot()));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMessage sent = service.sendMessage(MERCHANT_ID, LEAD_ID, "Olá", null);

        assertThat(sent.senderType().name()).isEqualTo("ADMIN");
        assertThat(meterRegistry.counter("foxcash_crm_messages_sent_total").count()).isEqualTo(1.0);
        verify(telegramApi).sendMessage("token", "u-1", "Olá");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.CHAT_MESSAGE_SENT), eq("LEAD"), eq(LEAD_ID), eq(MERCHANT_ID), any(Map.class));
    }

    @Test
    void sendMessageToBlockedLeadRejectsWith409() {
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(lead(CrmLeadStatus.BLOQUEADO)));

        assertThatThrownBy(() -> service.sendMessage(MERCHANT_ID, LEAD_ID, "Oi", null))
                .isInstanceOf(LeadBlockedException.class);
        assertThat(meterRegistry.counter("foxcash_crm_messages_sent_total").count()).isZero();
        verify(telegramApi, org.mockito.Mockito.never()).sendMessage(any(), any(), any());
    }

    @Test
    void telegramBlockedDuringSendMarksLeadAndIncrementsBlockedMetric() {
        when(leadRepository.findByIdAndMerchantId(MERCHANT_ID, LEAD_ID)).thenReturn(Optional.of(lead(CrmLeadStatus.NOVO)));
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID)).thenReturn(Optional.of(telegramBot()));
        org.mockito.Mockito.doThrow(new LeadBlockedException("Usuario bloqueou o bot (403)"))
                .when(telegramApi).sendMessage("token", "u-1", "Oi");

        assertThatThrownBy(() -> service.sendMessage(MERCHANT_ID, LEAD_ID, "Oi", null))
                .isInstanceOf(LeadBlockedException.class);

        assertThat(meterRegistry.counter("foxcash_crm_lead_blocked_total").count()).isEqualTo(1.0);
        verify(statusDerivation).markBlocked(BOT_ID, "u-1");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.LEAD_BLOCKED), eq("LEAD"), eq(LEAD_ID), eq(MERCHANT_ID), any(Map.class));
    }

    @Test
    void persistInboundSavesClientAndIncrementsReceivedMetric() {
        when(leadRepository.findIdByBotIdAndPlatformUserId(BOT_ID, "u-1")).thenReturn(Optional.of(LEAD_ID));
        when(messageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInbound(MERCHANT_ID, BOT_ID, "u-1", "preciso de ajuda", null);

        assertThat(meterRegistry.counter("foxcash_crm_messages_received_total").count()).isEqualTo(1.0);
        verify(messageRepository).save(any(ChatMessage.class));
        verify(eventPublisher).publish(eq(MERCHANT_ID), any(ChatEvent.class));
    }
}
