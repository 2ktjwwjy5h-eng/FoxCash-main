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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link ReceivableRecorder} (Spec 33): formaliza a dívida do lojista
 * quando o estorno (Spec 29) deixa o saldo NEGATIVO — saldo zero ou positivo
 * não cria recebível; recebível já aberto para a origem não é duplicado.
 */
class ReceivableRecorderTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final ReceivableRecorder recorder = new ReceivableRecorder(receivableRepository);

    @Test
    void zeroBalanceDoesNotCreateReceivable() {
        // Mutation (ConditionalsBoundary L38: signum() >= 0 → > 0): saldo
        // EXATAMENTE zero NÃO gera receivable — o mutante criaria dívida de 0.00.
        recorder.recordFromNegativeBalance(merchant("0.00"), ReceivableOrigin.CHARGEBACK,
                UUID.randomUUID(), Instant.now());

        verify(receivableRepository, never()).save(any());
        verify(receivableRepository, never()).findOpenByOrigin(any(), any(), any());
    }

    @Test
    void positiveBalanceDoesNotCreateReceivable() {
        recorder.recordFromNegativeBalance(merchant("10.00"), ReceivableOrigin.CHARGEBACK,
                UUID.randomUUID(), Instant.now());

        verify(receivableRepository, never()).save(any());
    }

    @Test
    void negativeBalanceCreatesReceivableWithDebtAmount() {
        UUID referenceId = UUID.randomUUID();
        when(receivableRepository.findOpenByOrigin(MERCHANT_ID, ReceivableOrigin.CHARGEBACK, referenceId))
                .thenReturn(Optional.empty());

        recorder.recordFromNegativeBalance(merchant("-150.00"), ReceivableOrigin.CHARGEBACK,
                referenceId, Instant.now());

        ArgumentCaptor<Receivable> captor = ArgumentCaptor.forClass(Receivable.class);
        verify(receivableRepository).save(captor.capture());
        // A dívida é o MÓDULO do saldo devedor (150 de dívida p/ -150 de saldo).
        assertThat(captor.getValue().amount().amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void existingOpenReceivableIsNotDuplicated() {
        UUID referenceId = UUID.randomUUID();
        when(receivableRepository.findOpenByOrigin(MERCHANT_ID, ReceivableOrigin.CHARGEBACK, referenceId))
                .thenReturn(Optional.of(Receivable.restore(UUID.randomUUID(), MERCHANT_ID,
                        ReceivableOrigin.CHARGEBACK, referenceId, Money.of("150.00"), Money.of("0.00"),
                        ReceivableStatus.OPEN, Instant.now(), null, Instant.now())));

        recorder.recordFromNegativeBalance(merchant("-150.00"), ReceivableOrigin.CHARGEBACK,
                referenceId, Instant.now());

        verify(receivableRepository, never()).save(any());
    }

    private Merchant merchant(String balance) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of(balance), false, Instant.now());
    }
}
