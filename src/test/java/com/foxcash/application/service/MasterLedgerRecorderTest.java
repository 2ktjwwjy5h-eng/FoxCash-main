package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterLedgerRecorder} (Spec 34): grava UMA entry por origem
 * com balance_after = ultimo saldo do lojista + amount; a recovery cumulativa
 * acumula na entry existente (id/created_at intactos).
 */
class MasterLedgerRecorderTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MasterLedgerRepository repository = mock(MasterLedgerRepository.class);
    private final MasterLedgerRecorder recorder = new MasterLedgerRecorder(repository);

    @Test
    void recordsEntryWithBalanceAfterEqualToLastPlusAmount() {
        when(repository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(
                Optional.of(entry(Money.of("50.00"))));
        when(repository.save(any(MasterLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MasterLedgerEntry returned = recorder.record(MERCHANT_ID, MasterLedgerEntryType.TRANSACTION_FEE,
                Money.of("10.00"), "INVOICE", UUID.randomUUID());

        // Mutation (NullReturnVals L48): record devolve a entry salva (não null).
        assertThat(returned).isNotNull();
        ArgumentCaptor<MasterLedgerEntry> captor = ArgumentCaptor.forClass(MasterLedgerEntry.class);
        verify(repository).save(captor.capture());
        MasterLedgerEntry saved = captor.getValue();
        assertThat(saved.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(saved.type()).isEqualTo(MasterLedgerEntryType.TRANSACTION_FEE);
        assertThat(saved.amount().amount()).isEqualByComparingTo("10.00");
        // balance_after = ultimo (50.00) + amount (10.00) = 60.00.
        assertThat(saved.balanceAfter().amount()).isEqualByComparingTo("60.00");
        assertThat(saved.createdAt()).isNotNull();
    }

    @Test
    void recordsWithZeroBaselineWhenNoPreviousEntry() {
        when(repository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(repository.save(any(MasterLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        recorder.record(MERCHANT_ID, MasterLedgerEntryType.MONTHLY_FEE,
                Money.of("69.95"), "INVOICE", UUID.randomUUID());

        ArgumentCaptor<MasterLedgerEntry> captor = ArgumentCaptor.forClass(MasterLedgerEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().balanceAfter().amount()).isEqualByComparingTo("69.95");
    }

    @Test
    void recordCumulativeInsertsOnFirstRecovery() {
        UUID receivableId = UUID.randomUUID();
        when(repository.findByReference(MERCHANT_ID, MasterLedgerEntryType.RECEIVABLE_RECOVERY,
                "RECEIVABLE", receivableId)).thenReturn(Optional.empty());
        when(repository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entry(Money.of("0.00"))));
        when(repository.save(any(MasterLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        MasterLedgerEntry returned = recorder.recordCumulative(MERCHANT_ID, MasterLedgerEntryType.RECEIVABLE_RECOVERY,
                Money.of("40.00"), "RECEIVABLE", receivableId);

        // Mutation (NullReturnVals L63): primeira recuperacao devolve a entry (não null).
        assertThat(returned).isNotNull();
        verify(repository).save(any(MasterLedgerEntry.class));
        verify(repository, org.mockito.Mockito.never()).updateAmountAndBalance(any(), any(), any());
    }

    @Test
    void recordCumulativeAccumulatesOnPartialRecovery() {
        // Recuperacao parcial em 2 etapas (Spec 34): a entry existente acumula o
        // total recebido e ajusta o balance_after — id/created_at permanecem.
        UUID receivableId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        MasterLedgerEntry existing = new MasterLedgerEntry(entryId, MERCHANT_ID,
                MasterLedgerEntryType.RECEIVABLE_RECOVERY, Money.of("40.00"), Money.of("40.00"),
                "RECEIVABLE", receivableId, Instant.now());
        when(repository.findByReference(MERCHANT_ID, MasterLedgerEntryType.RECEIVABLE_RECOVERY,
                "RECEIVABLE", receivableId)).thenReturn(Optional.of(existing));
        when(repository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entry(Money.of("40.00"))));
        when(repository.updateAmountAndBalance(entryId, Money.of("60.00").amount(),
                Money.of("60.00").amount())).thenReturn(existing);

        MasterLedgerEntry returned = recorder.recordCumulative(MERCHANT_ID, MasterLedgerEntryType.RECEIVABLE_RECOVERY,
                Money.of("20.00"), "RECEIVABLE", receivableId);

        // Mutation (NullReturnVals L66): recuperacao parcial devolve a entry (não null).
        assertThat(returned).isNotNull();
        // 40.00 (1a etapa) + 20.00 (2a etapa) = 60.00 acumulado; balance 60.00.
        verify(repository).updateAmountAndBalance(entryId,
                Money.of("60.00").amount(), Money.of("60.00").amount());
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private MasterLedgerEntry entry(Money balance) {
        return new MasterLedgerEntry(UUID.randomUUID(), MERCHANT_ID,
                MasterLedgerEntryType.TRANSACTION_FEE, balance, balance,
                "INVOICE", UUID.randomUUID(), Instant.now());
    }
}
