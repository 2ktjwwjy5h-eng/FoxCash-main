package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.botfunnel.FunnelEvent;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.BotFunnelEventRepository;
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
 * Testes do {@link BotFunnelService} (Spec 75): funil append-only
 * {@code lead → checkout_started → paid} por {@code (bot_id, platform_user_id)}.
 * {@code recordCheckout}/{@code recordPaid} idempotentes (replay do purchase /
 * do webhook PSP não duplica); cobrança sem botId → no-op (zero regressão).
 */
class BotFunnelServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final BotFunnelEventRepository repository = mock(BotFunnelEventRepository.class);
    private final TrackingDispatchService trackingDispatch = mock(TrackingDispatchService.class);
    private final LeadStatusDerivationService statusDerivation = mock(LeadStatusDerivationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BotFunnelService service = new BotFunnelService(repository, trackingDispatch, statusDerivation, clock);

    @Test
    void recordCheckoutCreatesWhenAbsent() {
        when(repository.findByBotIdAndTransactionId(BOT_ID, TX_ID)).thenReturn(Optional.empty());

        service.recordCheckout(BOT_ID, "u-1", TX_ID, Money.of("49.90"));

        ArgumentCaptor<BotFunnelEvent> captor = ArgumentCaptor.forClass(BotFunnelEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().event()).isEqualTo(FunnelEvent.CHECKOUT_STARTED);
        assertThat(captor.getValue().transactionId()).isEqualTo(TX_ID);
        assertThat(captor.getValue().occurredAt()).isEqualTo(NOW);
        // Spec 81: checkout novo → tracking na mesma transação.
        verify(trackingDispatch).onCheckout(BOT_ID, "u-1", TX_ID, Money.of("49.90"));
    }

    @Test
    void recordCheckoutIsNoOpOnReplay() {
        when(repository.findByBotIdAndTransactionId(BOT_ID, TX_ID))
                .thenReturn(Optional.of(BotFunnelEvent.create(BOT_ID, "u-1",
                        FunnelEvent.CHECKOUT_STARTED, TX_ID, NOW)));

        service.recordCheckout(BOT_ID, "u-1", TX_ID, Money.of("49.90"));

        verify(repository, never()).save(any());
        verify(trackingDispatch, never()).onCheckout(any(), any(), any(), any());
    }

    @Test
    void recordCheckoutIgnoresNullBotNullTransactionOrBlankUser() {
        service.recordCheckout(null, "u-1", TX_ID, Money.of("49.90"));
        service.recordCheckout(BOT_ID, "  ", TX_ID, Money.of("49.90"));
        service.recordCheckout(BOT_ID, "u-1", null, Money.of("49.90"));

        verify(repository, never()).save(any());
        verify(trackingDispatch, never()).onCheckout(any(), any(), any(), any());
    }

    @Test
    void recordPaidCreatesEventWithPaidAt() {
        when(repository.findByBotIdAndTransactionIdAndEvent(BOT_ID, TX_ID, FunnelEvent.PAID))
                .thenReturn(Optional.empty());

        service.recordPaid(transaction(TransactionStatus.APPROVED, NOW));

        ArgumentCaptor<BotFunnelEvent> captor = ArgumentCaptor.forClass(BotFunnelEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().event()).isEqualTo(FunnelEvent.PAID);
        assertThat(captor.getValue().transactionId()).isEqualTo(TX_ID);
        assertThat(captor.getValue().occurredAt()).isEqualTo(NOW); // usa o paidAt da transação
        // Spec 81: paid novo → tracking de Purchase com o valor da transação.
        verify(trackingDispatch).onPurchase(BOT_ID, "u-1", TX_ID, Money.of("49.90"));
    }

    @Test
    void recordPaidIsNotBlockedByExistingCheckoutStarted() {
        // Regressão (descoberta no E2E bot-funnel): a idempotência do PAID NÃO
        // pode usar o mesmo lookup do recordCheckout — o CHECKOUT_STARTED da
        // MESMA transação já existe e não é replay de PAID (Spec 75/F1).
        when(repository.findByBotIdAndTransactionId(BOT_ID, TX_ID))
                .thenReturn(Optional.of(BotFunnelEvent.create(BOT_ID, "u-1",
                        FunnelEvent.CHECKOUT_STARTED, TX_ID, NOW)));
        when(repository.findByBotIdAndTransactionIdAndEvent(BOT_ID, TX_ID, FunnelEvent.PAID))
                .thenReturn(Optional.empty());

        service.recordPaid(transaction(TransactionStatus.APPROVED, NOW));

        ArgumentCaptor<BotFunnelEvent> captor = ArgumentCaptor.forClass(BotFunnelEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().event()).isEqualTo(FunnelEvent.PAID);
    }

    @Test
    void recordPaidIsNoOpOnReplay() {
        when(repository.findByBotIdAndTransactionIdAndEvent(BOT_ID, TX_ID, FunnelEvent.PAID))
                .thenReturn(Optional.of(BotFunnelEvent.create(BOT_ID, "u-1",
                        FunnelEvent.PAID, TX_ID, NOW)));

        service.recordPaid(transaction(TransactionStatus.APPROVED, NOW));

        verify(repository, never()).save(any());
        verify(trackingDispatch, never()).onPurchase(any(), any(), any(), any());
    }

    @Test
    void recordPaidIgnoresTransactionWithoutBot() {
        // Cobrança avulsa (bot_id NULL) — zero regressão (Spec 75/T1.3).
        PixTransaction noBot = PixTransaction.restore(TX_ID, UUID.randomUUID(), Money.of("10.00"),
                "Cobranca", null, null, "REF", null, null, TransactionStatus.APPROVED,
                NOW, NOW, NOW, null, null, null, null, null, "u-1", null, null, null);

        service.recordPaid(noBot);

        verify(repository, never()).save(any());
    }

    private PixTransaction transaction(TransactionStatus status, Instant paidAt) {
        return PixTransaction.restore(TX_ID, UUID.randomUUID(), Money.of("49.90"), "Plano",
                null, null, "REF", null, null, status, NOW, NOW, paidAt,
                null, null, null, BOT_ID, null, "u-1", null, null, null);
    }
}
