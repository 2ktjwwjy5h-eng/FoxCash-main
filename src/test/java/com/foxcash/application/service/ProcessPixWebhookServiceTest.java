package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.ProcessPixWebhookUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReconciliationIssueRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Testes do {@link ProcessPixWebhookService} (Spec 18): replay de evento já
 * aplicado é idempotente (no-op, sem 409); transições válidas persistem.
 */
class ProcessPixWebhookServiceTest {

    private static final String EXTERNAL_REFERENCE = "EE72C72E2059F45DCADB2CB20AC8AFEB4";
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WalletLedgerRecorder ledgerRecorder = mock(WalletLedgerRecorder.class);
    private final ReceivableRecorder receivableRecorder = mock(ReceivableRecorder.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);
    private ProcessPixWebhookService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final ReconciliationIssueRepository reconciliationIssues = mock(ReconciliationIssueRepository.class);
    private final DeliveryRecorder deliveryRecorder = mock(DeliveryRecorder.class);
    private final BotFunnelService botFunnelService = mock(BotFunnelService.class);
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new ProcessPixWebhookService(repository, merchantRepository, ledgerRecorder,
                receivableRecorder, webhookEventPublisher, activityRecorder, reconciliationIssues,
                deliveryRecorder, botFunnelService, meterRegistry);
    }

    private PixTransaction transactionWithStatus(TransactionStatus status) {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID, Money.of("150.00"),
                "Cobranca", null, EXTERNAL_REFERENCE, EXTERNAL_REFERENCE,
                null, null, status, Instant.now(), Instant.now(), null);
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725", null,
                MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, Instant.now());
    }

    @Test
    void replayOfAlreadyApprovedPaidIsIdempotent() {
        PixTransaction approved = transactionWithStatus(TransactionStatus.APPROVED);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(approved));

        // Não lança exceção e não persiste — replay tolerado (nem credita o wallet).
        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID"));

        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void pendingPaidTransitionsToApprovedAndSavesAndCreditsWallet() {
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        Merchant merchant = merchant();
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID"));

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.APPROVED);

        // Wallet do lojista creditado com o valor aprovado (fonte de saldo dos saques).
        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().walletBalance().amount()).isEqualByComparingTo("150.00");

        // Ledger (Spec 27): 1 entry SALE_CREDIT na mesma transação.
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.SALE_CREDIT),
                eq(Money.of("150.00")), eq("TRANSACTION"), eq(pending.id()));

        // Webhook de saída (Spec 35) + entrega (Spec 49) na mesma transação.
        // Spec 125 (trace): o evento carrega a referência da transação.
        // Spec 127: data.traceId explícito — o fio condutor dos logs (MDC do worker).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(webhookEventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.SALE_PAID),
                dataCaptor.capture(), eq("TRANSACTION"), eq(pending.id()));
        assertThat(dataCaptor.getValue()).containsEntry("traceId", pending.traceId());
        assertThat(dataCaptor.getValue()).containsEntry("transactionId", pending.id());
        verify(deliveryRecorder).recordFor(pending);
    }

    @Test
    void approvedPaidWithUnknownMerchantThrowsNotFound() {
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(
                new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID")))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void invalidStatusIsRejected() {
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.process(
                new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "FOO")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void unknownReferenceThrowsNotFound() {
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(
                new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID")))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void refundedTransitionsToRefundedAndDebitsWallet() {
        PixTransaction approved = transactionWithStatus(TransactionStatus.APPROVED);
        Merchant merchant = merchant();
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(approved));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REFUNDED"));

        // Transação marcada como REFUNDED e o valor sai do wallet (0 - 150 = -150:
        // o lojista deve à plataforma quando o estorno chega após o saque).
        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.REFUNDED);

        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().walletBalance().amount()).isEqualByComparingTo("-150.00");

        // Ledger (Spec 27): 1 entry REFUND_DEBIT na mesma transação.
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.REFUND_DEBIT),
                eq(Money.of("150.00")), eq("TRANSACTION"), eq(approved.id()));

        // Webhook de saída (Spec 35) + divida formal (Spec 33): saldo negativo
        // após o estorno → receivable REFUND na MESMA transação.
        // Spec 125 (trace): o evento carrega a referência da transação.
        verify(webhookEventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.SALE_REFUNDED),
                any(Map.class), eq("TRANSACTION"), eq(approved.id()));
        verify(receivableRecorder).recordFromNegativeBalance(any(), eq(ReceivableOrigin.REFUND),
                eq(approved.id()), any());
    }

    @Test
    void replayOfAlreadyRefundedIsIdempotent() {
        PixTransaction refunded = transactionWithStatus(TransactionStatus.REFUNDED);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(refunded));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REFUNDED"));

        // Replay tolerado: não lança conflito nem debita o wallet de novo.
        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void latePaidAfterRefundedIsToleratedNoOp() {
        // Auditoria 2026-08-05 (M13): PAID tardio para transacao ja REFUNDED e
        // NO-OP (antes: 500 e PSP reenvia para sempre) — nao re-credita o wallet.
        PixTransaction refunded = transactionWithStatus(TransactionStatus.REFUNDED);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(refunded));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID"));

        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void refundedForNeverPaidPendingIsToleratedNoOp() {
        // Auditoria 2026-08-05 (M13): REFUNDED de cobranca nunca paga (PENDING)
        // e NO-OP — sem debito (nunca houve credito) e sem receivable.
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REFUNDED"));

        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void rejectedForApprovedIsToleratedNoOp() {
        // Auditoria 2026-08-05 (M13): REJECTED tardio para transacao ja paga e
        // NO-OP — nao "desaprova" uma venda ja creditada.
        PixTransaction approved = transactionWithStatus(TransactionStatus.APPROVED);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(approved));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REJECTED"));

        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
    }

    // ---------- B-05 (auditoria 2026-08-05): validação do amount no webhook PAID ----------

    @Test
    void paidWithMatchingAmountApprovesNormally() {
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        Merchant merchant = merchant();
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID",
                new java.math.BigDecimal("150.00")));

        // Fluxo normal: aprova, credita e NÃO abre issue de reconciliação.
        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.APPROVED);
        verify(reconciliationIssues, never()).open(any(), any(), any(), any());
    }

    @Test
    void paidWithDivergentAmountIsNoOpAndOpensReconciliationIssue() {
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));

        // B-05: valor divergente NÃO aprova às cegas — no-op + issue para o operador.
        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID",
                new java.math.BigDecimal("150.01")));

        verify(repository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
        // Issue de reconciliação registrada (esperado = registro local, actual = PSP).
        verify(reconciliationIssues).open(eq(MERCHANT_ID), eq(Money.of("150.00")),
                eq(Money.of("150.01")), any());
        // Spec 126: divergência contabilizada no counter financeiro (B-05).
        assertThat(meterRegistry.counter("foxcash_webhook_amount_divergence_total").count())
                .isEqualTo(1);
    }

    @Test
    void paidWithoutAmountStillApproves() {
        // PSP que não envia amount → confia no registro (comportamento atual).
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        Merchant merchant = merchant();
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "PAID", null));

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.APPROVED);
        verify(reconciliationIssues, never()).open(any(), any(), any(), any());
    }

    @Test
    void rejectedPendingIsRejectedAndSaved() {
        // L111: REJECTED de cobrança PENDING → reject() + save (mutante remove
        // a chamada e a transação nunca vira REJECTED).
        PixTransaction pending = transactionWithStatus(TransactionStatus.PENDING);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(pending));

        service.process(new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REJECTED"));

        ArgumentCaptor<PixTransaction> captor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.REJECTED);
        verify(merchantRepository, never()).save(any());
    }

    @Test
    void refundedWithUnknownMerchantThrowsNotFound() {
        // L192: o estorno exige o lojista para debitar — merchant ausente →
        // MerchantNotFoundException (mutante do lambda retornaria null → NPE).
        PixTransaction approved = transactionWithStatus(TransactionStatus.APPROVED);
        when(repository.findByExternalReferenceForUpdate(EXTERNAL_REFERENCE)).thenReturn(Optional.of(approved));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(
                new ProcessPixWebhookUseCase.WebhookEvent(EXTERNAL_REFERENCE, "REFUNDED")))
                .isInstanceOf(MerchantNotFoundException.class);
        verify(merchantRepository, never()).save(any());
    }
}
