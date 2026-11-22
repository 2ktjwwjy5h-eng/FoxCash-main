package com.foxcash.application.service;

import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.port.out.BotLeadRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link BotLeadService} (Spec 75): registro de lead idempotente —
 * primeiro registro cria; replay de update (Telegram) / interação (Discord) é
 * no-op (UNIQUE bot_id + platform_user_id); update sem usuário não é lead.
 * Spec 81/D4: UTM gravadas na criação (primeiro valor vence) + tracking de
 * {@code lead} disparado só na criação.
 */
class BotLeadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID BOT_ID = UUID.randomUUID();

    private final BotLeadRepository repository = mock(BotLeadRepository.class);
    private final TrackingDispatchService trackingDispatch = mock(TrackingDispatchService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BotLeadService service = new BotLeadService(repository, trackingDispatch, clock);

    @Test
    void recordLeadCreatesWhenAbsent() {
        when(repository.findByBotIdAndPlatformUserId(BOT_ID, "u-1")).thenReturn(Optional.empty());
        when(repository.saveIfAbsent(any(BotLead.class))).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        service.recordLead(BOT_ID, "u-1");

        ArgumentCaptor<BotLead> captor = ArgumentCaptor.forClass(BotLead.class);
        verify(repository).saveIfAbsent(captor.capture());
        assertThat(captor.getValue().botId()).isEqualTo(BOT_ID);
        assertThat(captor.getValue().platformUserId()).isEqualTo("u-1");
        assertThat(captor.getValue().firstSeenAt()).isEqualTo(NOW); // primeira vez vista
    }

    @Test
    void recordLeadWithUtmPersistsOnCreationAndFiresTracking() {
        when(repository.findByBotIdAndPlatformUserId(BOT_ID, "u-1")).thenReturn(Optional.empty());
        when(repository.saveIfAbsent(any(BotLead.class))).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        UtmParams utm = UtmParams.of("fb", null, "promo", null, null);

        service.recordLead(BOT_ID, "u-1", utm);

        ArgumentCaptor<BotLead> captor = ArgumentCaptor.forClass(BotLead.class);
        verify(repository).saveIfAbsent(captor.capture());
        assertThat(captor.getValue().utm()).isNotNull();
        assertThat(captor.getValue().utm().source()).isEqualTo("fb");
        assertThat(captor.getValue().utm().campaign()).isEqualTo("promo");
        // Spec 81: novo lead → tracking na mesma transação.
        verify(trackingDispatch).onLead(BOT_ID, "u-1", utm);
    }

    @Test
    void recordLeadIsNoOpOnReplay() {
        when(repository.findByBotIdAndPlatformUserId(BOT_ID, "u-1"))
                .thenReturn(Optional.of(BotLead.create(BOT_ID, "u-1", NOW)));

        service.recordLead(BOT_ID, "u-1");

        verify(repository, never()).saveIfAbsent(any());
        verify(trackingDispatch, never()).onLead(any(), any(), any());
    }

    @Test
    void recordLeadIgnoresBlankOrNullUserAndNullBot() {
        service.recordLead(BOT_ID, null);
        service.recordLead(BOT_ID, "  ");
        service.recordLead(null, "u-1");

        verify(repository, never()).findByBotIdAndPlatformUserId(any(), any());
        verify(repository, never()).saveIfAbsent(any());
        verify(trackingDispatch, never()).onLead(any(), any(), any());
    }
}
