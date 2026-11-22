package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do receivable (Spec 33): ciclo da divida formal — criacao, pagamento
 * parcial ate quitar (PARTIAL → PAID) e o {@link ReceivableRecorder}, que
 * formaliza a divida quando o estorno deixa o saldo negativo (Spec 29).
 */
class ReceivableTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID REFERENCE_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final ReceivableRecorder recorder = new ReceivableRecorder(receivableRepository);

    private Merchant merchant(String balance) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of(balance), false, NOW);
    }

    @Test
    void createOpensDebtWithZeroPaid() {
        Receivable receivable = Receivable.create(MERCHANT_ID, ReceivableOrigin.CHARGEBACK,
                REFERENCE_ID, Money.of("150.00"), NOW);

        assertThat(receivable.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(receivable.paidAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(receivable.remaining().amount()).isEqualByComparingTo("150.00");
        assertThat(receivable.paidAt()).isNull();
    }

    @Test
    void partialPaymentMovesToPartialUntilFullyPaid() {
        Receivable receivable = Receivable.create(MERCHANT_ID, ReceivableOrigin.REFUND,
                REFERENCE_ID, Money.of("100.00"), NOW);

        receivable.applyPayment(Money.of("40.00"), NOW);
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PARTIAL);
        assertThat(receivable.paidAmount().amount()).isEqualByComparingTo("40.00");
        assertThat(receivable.remaining().amount()).isEqualByComparingTo("60.00");

        receivable.applyPayment(Money.of("60.00"), NOW);
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PAID);
        assertThat(receivable.paidAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(receivable.paidAt()).isEqualTo(NOW);
        assertThat(receivable.isFullyPaid()).isTrue();
    }

    @Test
    void zeroOrNegativePaymentIsNoop() {
        Receivable receivable = Receivable.create(MERCHANT_ID, ReceivableOrigin.ADJUSTMENT,
                REFERENCE_ID, Money.of("100.00"), NOW);

        receivable.applyPayment(Money.of("0.00"), NOW);

        assertThat(receivable.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(receivable.paidAmount().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void recorderCreatesChargebackReceivableWhenBalanceGoesNegative() {
        // Estorno de 150 sobre saldo 100 -> saldo -50 -> divida de 50.
        Merchant merchant = merchant("-50.00");
        when(receivableRepository.findOpenByOrigin(MERCHANT_ID, ReceivableOrigin.CHARGEBACK, REFERENCE_ID))
                .thenReturn(Optional.empty());

        recorder.recordFromNegativeBalance(merchant, ReceivableOrigin.CHARGEBACK, REFERENCE_ID, NOW);

        ArgumentCaptor<Receivable> captor = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository).save(captor.capture());
        Receivable saved = captor.getValue();
        assertThat(saved.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(saved.origin()).isEqualTo(ReceivableOrigin.CHARGEBACK);
        assertThat(saved.referenceId()).isEqualTo(REFERENCE_ID);
        assertThat(saved.amount().amount()).isEqualByComparingTo("50.00");
        assertThat(saved.status()).isEqualTo(ReceivableStatus.OPEN);
    }

    @Test
    void recorderDoesNothingWhenBalanceIsPositiveOrZero() {
        Merchant merchant = merchant("100.00");

        recorder.recordFromNegativeBalance(merchant, ReceivableOrigin.REFUND, REFERENCE_ID, NOW);

        verify(receivableRepository, never()).save(any());
        verify(receivableRepository, never()).findOpenByOrigin(any(), any(), any());
    }

    @Test
    void recorderIsIdempotentWhenReceivableAlreadyOpen() {
        Merchant merchant = merchant("-50.00");
        Receivable existing = Receivable.create(MERCHANT_ID, ReceivableOrigin.REFUND,
                REFERENCE_ID, Money.of("50.00"), NOW);
        when(receivableRepository.findOpenByOrigin(MERCHANT_ID, ReceivableOrigin.REFUND, REFERENCE_ID))
                .thenReturn(Optional.of(existing));

        recorder.recordFromNegativeBalance(merchant, ReceivableOrigin.REFUND, REFERENCE_ID, NOW);

        verify(receivableRepository, never()).save(any());
    }

    @Test
    void recorderUsesReferenceIdOfOrigin() {
        Merchant merchant = merchant("-80.00");
        when(receivableRepository.findOpenByOrigin(eq(MERCHANT_ID), eq(ReceivableOrigin.CHARGEBACK), eq(REFERENCE_ID)))
                .thenReturn(Optional.empty());

        recorder.recordFromNegativeBalance(merchant, ReceivableOrigin.CHARGEBACK, REFERENCE_ID, NOW);

        verify(receivableRepository).save(any(Receivable.class));
    }
}
