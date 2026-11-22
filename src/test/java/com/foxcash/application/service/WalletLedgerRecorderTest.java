package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link WalletLedgerRecorder} (Spec 27): grava UMA entry por mutação
 * com balance_after = saldo pós-mutação (o chamador já mutou o merchant).
 */
class WalletLedgerRecorderTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final WalletLedgerRepository repository = mock(WalletLedgerRepository.class);
    private final WalletLedgerRecorder recorder = new WalletLedgerRecorder(repository);

    @Test
    void recordsEntryWithBalanceAfterEqualToMerchantBalance() {
        Merchant merchant = merchant("60.00");
        when(repository.save(any(WalletLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletLedgerEntry returned = recorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
                Money.of("40.00"), "WITHDRAWAL", UUID.randomUUID());

        // Mutation (NullReturnVals L45): record devolve a entry salva (não null).
        assertThat(returned).isNotNull();
        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(repository).save(captor.capture());
        WalletLedgerEntry entry = captor.getValue();
        assertThat(entry.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(entry.type()).isEqualTo(WalletLedgerEntryType.WITHDRAWAL_DEBIT);
        assertThat(entry.amount().amount()).isEqualByComparingTo("40.00");
        // balance_after reflete o saldo já mutado pelo chamador.
        assertThat(entry.balanceAfter().amount()).isEqualByComparingTo("60.00");
        assertThat(entry.referenceType()).isEqualTo("WITHDRAWAL");
        assertThat(entry.createdAt()).isNotNull();
    }

    @Test
    void recordsEachTypeWithItsOwnReference() {
        Merchant merchant = merchant("0.00");
        when(repository.save(any(WalletLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.record(merchant, WalletLedgerEntryType.SALE_CREDIT, Money.of("150.00"),
                "TRANSACTION", UUID.randomUUID());

        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(WalletLedgerEntryType.SALE_CREDIT);
    }

    @Test
    void recordCumulativeInsertsOnFirstCollection() {
        Merchant merchant = merchant("50.00");
        UUID invoiceId = UUID.randomUUID();
        when(repository.findByReference(MERCHANT_ID, WalletLedgerEntryType.INVOICE_FEE_DEBIT,
                "INVOICE", invoiceId)).thenReturn(java.util.Optional.empty());
        when(repository.save(any(WalletLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletLedgerEntry returned = recorder.recordCumulative(merchant, WalletLedgerEntryType.INVOICE_FEE_DEBIT,
                Money.of("50.00"), "INVOICE", invoiceId);

        // Mutation (NullReturnVals L60): primeira cobranca devolve a entry (não null).
        assertThat(returned).isNotNull();
        verify(repository).save(any(WalletLedgerEntry.class));
        verify(repository, org.mockito.Mockito.never()).updateAmountAndBalance(any(), any(), any());
    }

    @Test
    void recordCumulativeAccumulatesOnRecollection() {
        // Re-cobranca da fatura (Spec 33): entry existente acumula o total e
        // ajusta o balance_after — id/created_at permanecem (imutabilidade da origem).
        Merchant merchant = merchant("29.05");
        UUID invoiceId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        WalletLedgerEntry existing = new WalletLedgerEntry(entryId, MERCHANT_ID,
                WalletLedgerEntryType.INVOICE_FEE_DEBIT, Money.of("70.95"), Money.of("49.05"),
                "INVOICE", invoiceId, Instant.now());
        when(repository.findByReference(MERCHANT_ID, WalletLedgerEntryType.INVOICE_FEE_DEBIT,
                "INVOICE", invoiceId)).thenReturn(java.util.Optional.of(existing));
        when(repository.updateAmountAndBalance(entryId, Money.of("100.00").amount(),
                Money.of("29.05").amount())).thenReturn(existing);

        WalletLedgerEntry returned = recorder.recordCumulative(merchant, WalletLedgerEntryType.INVOICE_FEE_DEBIT,
                Money.of("29.05"), "INVOICE", invoiceId);

        // Mutation (NullReturnVals L63): re-cobranca devolve a entry atualizada (não null).
        assertThat(returned).isNotNull();
        // 70.95 (primeira cobranca) + 29.05 (re-cobranca) = 100.00 acumulado.
        verify(repository).updateAmountAndBalance(entryId,
                Money.of("100.00").amount(), Money.of("29.05").amount());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private Merchant merchant(String balance) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of(balance), false, Instant.now());
    }
}
