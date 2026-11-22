package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.botfunnel.FunnelEvent;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.BotFunnelEventRepository;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link TransactionTraceService} (Spec 125): lookup por UUID /
 * externalReference / idempotencyKey, timeline completa e parcial (sem bot),
 * estorno e entrega do webhook no outbox (fase 2).
 */
class TransactionTraceServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final PlatformActivityRepository activityRepository = mock(PlatformActivityRepository.class);
    private final WalletLedgerRepository walletLedgerRepository = mock(WalletLedgerRepository.class);
    private final MasterLedgerRepository masterLedgerRepository = mock(MasterLedgerRepository.class);
    private final BotFunnelEventRepository funnelRepository = mock(BotFunnelEventRepository.class);
    private final NotificationOutboxPort outbox = mock(NotificationOutboxPort.class);

    private TransactionTraceService service;

    @BeforeEach
    void setUp() {
        service = new TransactionTraceService(transactionRepository, activityRepository,
                walletLedgerRepository, masterLedgerRepository, funnelRepository, outbox,
                new ObjectMapper());
    }

    private PixTransaction approvedTx() {
        PixTransaction tx = PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("150.00"),
                "Cobranca", "v1:enc", "E0001", "code-psp-1", null, null,
                TransactionStatus.APPROVED, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(120), null, "cob-key-1", null, BOT_ID, null,
                "user-123", null, null, null);
        tx.attachProfile(PROFILE_ID);
        return tx;
    }

    /** Mock de todas as fontes de uma jornada completa (fluxo feliz com bot). */
    private void mockFullJourney(PixTransaction tx) {
        when(transactionRepository.findById(TX_ID)).thenReturn(Optional.of(tx));
        when(transactionRepository.findByExternalReference("code-psp-1")).thenReturn(Optional.of(tx));
        when(transactionRepository.findByIdempotencyKey("cob-key-1")).thenReturn(Optional.of(tx));

        when(activityRepository.query(any())).thenReturn(PageResult.of(List.of(
                PlatformActivity.record(PlatformActorType.SYSTEM, null, PlatformAction.CHARGE_CREATED,
                        "TRANSACTION", TX_ID, MERCHANT_ID, "{\"amount\":\"150.00\"}", null, NOW.minusSeconds(500)),
                PlatformActivity.record(PlatformActorType.SYSTEM, null, PlatformAction.SALE_PAID,
                        "TRANSACTION", TX_ID, MERCHANT_ID, "{\"amount\":\"150.00\"}", null, NOW.minusSeconds(100))),
                0, 100, 2));
        when(walletLedgerRepository.findByReferenceTypeAndReferenceId("TRANSACTION", TX_ID))
                .thenReturn(List.of(WalletLedgerEntry.record(MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT,
                        Money.of("150.00"), Money.of("820.00"), "TRANSACTION", TX_ID, NOW.minusSeconds(100))));
        when(masterLedgerRepository.findByReferenceTypeAndReferenceId("TRANSACTION", TX_ID))
                .thenReturn(List.of(MasterLedgerEntry.record(MERCHANT_ID, MasterLedgerEntryType.TRANSACTION_FEE,
                        Money.of("0.50"), Money.of("0.50"), "TRANSACTION", TX_ID, NOW.minusSeconds(100))));
        when(funnelRepository.findByTransactionId(TX_ID))
                .thenReturn(List.of(
                        BotFunnelEvent.create(BOT_ID, "user-123", FunnelEvent.CHECKOUT_STARTED, TX_ID, NOW.minusSeconds(550)),
                        BotFunnelEvent.create(BOT_ID, "user-123", FunnelEvent.PAID, TX_ID, NOW.minusSeconds(90))));
        when(outbox.findByEntity("TRANSACTION", TX_ID))
                .thenReturn(List.of(new OutboxNotification(UUID.randomUUID(), MERCHANT_ID, "SALE_PAID",
                        "{}", NOW.minusSeconds(50), 1, null, null, "TRANSACTION", TX_ID, "SENT")));
    }

    @Test
    void lookupByUuidBuildsFullJourneyInOrder() {
        mockFullJourney(approvedTx());

        TransactionTraceService.TransactionTrace trace = service.trace(TX_ID.toString());

        assertThat(trace.transaction().id()).isEqualTo(TX_ID);
        // 2 activity + 1 wallet + 1 master + 2 funil + 1 outbox = 7
        assertThat(trace.timeline()).hasSize(7);
        // Ordenada por timestamp
        assertThat(trace.timeline().stream().map(TransactionTraceService.TraceItem::at).toList())
                .isSorted();
        // Primeira etapa: BOT CHECKOUT_STARTED; última: WEBHOOK SALE_PAID.
        assertThat(trace.timeline().get(0).stage()).isEqualTo(TransactionTraceService.TraceStage.BOT);
        assertThat(trace.timeline().get(0).event()).isEqualTo("CHECKOUT_STARTED");
        assertThat(trace.timeline().get(6).stage()).isEqualTo(TransactionTraceService.TraceStage.WEBHOOK);
        assertThat(trace.timeline().get(6).event()).isEqualTo("SALE_PAID");
    }

    @Test
    void lookupByExternalReferenceResolvesSameTrace() {
        mockFullJourney(approvedTx());

        TransactionTraceService.TransactionTrace trace = service.trace("code-psp-1");

        assertThat(trace.transaction().externalReference()).isEqualTo("code-psp-1");
        assertThat(trace.timeline()).hasSize(7);
    }

    @Test
    void lookupByIdempotencyKeyResolvesSameTrace() {
        mockFullJourney(approvedTx());

        TransactionTraceService.TransactionTrace trace = service.trace("cob-key-1");

        assertThat(trace.transaction().idempotencyKey()).isEqualTo("cob-key-1");
        assertThat(trace.timeline()).hasSize(7);
    }

    @Test
    void unknownRefThrowsNotFound() {
        when(transactionRepository.findById(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByExternalReference(any())).thenReturn(Optional.empty());
        when(transactionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trace("nao-existe"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void transactionWithoutBotHasNoBotStage() {
        // Sem botId na transação e sem eventos de funil → timeline sem stage BOT.
        PixTransaction tx = PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("150.00"),
                "Cobranca", null, null, "code-psp-1", null, null,
                TransactionStatus.APPROVED, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(120), null, "cob-key-1", null, null, null,
                null, null, null, null);
        when(transactionRepository.findById(TX_ID)).thenReturn(Optional.of(tx));
        when(activityRepository.query(any())).thenReturn(PageResult.of(List.of(
                PlatformActivity.record(PlatformActorType.SYSTEM, null, PlatformAction.CHARGE_CREATED,
                        "TRANSACTION", TX_ID, MERCHANT_ID, "{\"amount\":\"150.00\"}", null, NOW.minusSeconds(500))),
                0, 100, 1));
        when(walletLedgerRepository.findByReferenceTypeAndReferenceId(any(), any())).thenReturn(List.of());
        when(masterLedgerRepository.findByReferenceTypeAndReferenceId(any(), any())).thenReturn(List.of());
        when(funnelRepository.findByTransactionId(TX_ID)).thenReturn(List.of());
        when(outbox.findByEntity(any(), any())).thenReturn(List.of());

        TransactionTraceService.TransactionTrace trace = service.trace(TX_ID.toString());

        assertThat(trace.timeline()).hasSize(1);
        assertThat(trace.timeline().get(0).stage()).isEqualTo(TransactionTraceService.TraceStage.GATEWAY);
        assertThat(trace.timeline()).extracting(TransactionTraceService.TraceItem::stage)
                .doesNotContain(TransactionTraceService.TraceStage.BOT);
    }

    @Test
    void refundShowsRefundDebitAndSaleRefunded() {
        PixTransaction tx = PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("150.00"),
                "Cobranca", null, null, "code-psp-1", null, null,
                TransactionStatus.REFUNDED, NOW.minusSeconds(600), NOW.minusSeconds(60),
                NOW.minusSeconds(30), null, "cob-key-1", null, null, null,
                null, null, null, null);
        when(transactionRepository.findById(TX_ID)).thenReturn(Optional.of(tx));
        when(activityRepository.query(any())).thenReturn(PageResult.of(List.of(
                PlatformActivity.record(PlatformActorType.SYSTEM, null, PlatformAction.SALE_REFUNDED,
                        "TRANSACTION", TX_ID, MERCHANT_ID, "{\"amount\":\"150.00\"}", null, NOW.minusSeconds(30))),
                0, 100, 1));
        when(walletLedgerRepository.findByReferenceTypeAndReferenceId(any(), any()))
                .thenReturn(List.of(WalletLedgerEntry.record(MERCHANT_ID, WalletLedgerEntryType.REFUND_DEBIT,
                        Money.of("150.00"), Money.of("670.00"), "TRANSACTION", TX_ID, NOW.minusSeconds(30))));
        when(masterLedgerRepository.findByReferenceTypeAndReferenceId(any(), any())).thenReturn(List.of());
        when(funnelRepository.findByTransactionId(any())).thenReturn(List.of());
        when(outbox.findByEntity(any(), any())).thenReturn(List.of());

        TransactionTraceService.TransactionTrace trace = service.trace(TX_ID.toString());

        assertThat(trace.timeline())
                .extracting(TransactionTraceService.TraceItem::event)
                .contains("SALE_REFUNDED", "REFUND_DEBIT");
        assertThat(trace.timeline())
                .extracting(TransactionTraceService.TraceItem::stage)
                .contains(TransactionTraceService.TraceStage.PSP, TransactionTraceService.TraceStage.LEDGER);
    }

    @Test
    void outboxDeliveryCarriesStatusAndAttempts() {
        mockFullJourney(approvedTx());

        TransactionTraceService.TransactionTrace trace = service.trace(TX_ID.toString());

        TransactionTraceService.TraceItem webhook = trace.timeline().get(6);
        assertThat(webhook.stage()).isEqualTo(TransactionTraceService.TraceStage.WEBHOOK);
        assertThat(webhook.detail()).containsEntry("status", "SENT");
        assertThat(webhook.detail()).containsEntry("attempts", 1);
    }
}
