package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.in.MasterRevenueUseCase;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterRevenueService} (Spec 34): summary = SOMA por tipo no
 * período + net (entradas − perdas); ledger paginado com filtros.
 */
class MasterRevenueServiceTest {

    private final MasterLedgerRepository repository = mock(MasterLedgerRepository.class);
    private final MasterRevenueService service = new MasterRevenueService(repository);

    @Test
    void summarySumsByTypeAndComputesNet() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T23:59:59Z");
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.TRANSACTION_FEE, from, to))
                .thenReturn(new BigDecimal("1.50"));
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.MONTHLY_FEE, from, to))
                .thenReturn(new BigDecimal("69.95"));
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.RECEIVABLE_RECOVERY, from, to))
                .thenReturn(new BigDecimal("100.00"));
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.ADMIN_RECOVERY, from, to))
                .thenReturn(new BigDecimal("20.00"));
        // Spec 88: taxa de saque (3 saques × 2,50) — receita própria do master.
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.WITHDRAWAL_FEE, from, to))
                .thenReturn(new BigDecimal("7.50"));
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.WITHDRAWAL_FEE_REVERSAL, from, to))
                .thenReturn(BigDecimal.ZERO);
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.CHARGEBACK_LOSS, from, to))
                .thenReturn(new BigDecimal("30.00"));
        // SEC-29: reversão de saque administrativo (perda) — zero neste cenário.
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL, from, to))
                .thenReturn(BigDecimal.ZERO);

        MasterRevenueUseCase.RevenueSummary summary = service.summary(from, to);

        assertThat(summary.transactionFees()).isEqualByComparingTo("1.50");
        assertThat(summary.monthlyFees()).isEqualByComparingTo("69.95");
        assertThat(summary.recoveries()).isEqualByComparingTo("100.00");
        assertThat(summary.adminRecoveries()).isEqualByComparingTo("20.00");
        assertThat(summary.withdrawalFees()).isEqualByComparingTo("7.50");
        assertThat(summary.losses()).isEqualByComparingTo("30.00");
        // total = soma de TODAS as entries (inclui perdas); net = entradas - perdas.
        assertThat(summary.total()).isEqualByComparingTo("228.95");
        assertThat(summary.net()).isEqualByComparingTo("168.95");
    }

    @Test
    void summarySubtractsAdminRecoveryReversalsFromNet() {
        // SEC-29 (revisão de segurança 2026-08-11): um saque administrativo que
        // falhou teve a receita (ADMIN_RECOVERY) revertida — a reversão conta
        // como perda: o net (entradas − perdas) NÃO pode mostrar o valor
        // recolhido que não ocorreu.
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T23:59:59Z");
        // Stub genérico PRIMEIRO (o último stub vence no Mockito): os demais
        // tipos retornam zero; os específicos abaixo prevalecem.
        when(repository.sumAmountByTypeBetween(any(), eq(from), eq(to)))
                .thenReturn(BigDecimal.ZERO);
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.ADMIN_RECOVERY, from, to))
                .thenReturn(new BigDecimal("100.00"));
        when(repository.sumAmountByTypeBetween(MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL, from, to))
                .thenReturn(new BigDecimal("40.00"));

        MasterRevenueUseCase.RevenueSummary summary = service.summary(from, to);

        // adminRecoveries 100 − reversão 40 = 60 líquido; perdas = 40.
        assertThat(summary.adminRecoveries()).isEqualByComparingTo("100.00");
        assertThat(summary.losses()).isEqualByComparingTo("40.00");
        assertThat(summary.net()).isEqualByComparingTo("60.00");
        assertThat(summary.total()).isEqualByComparingTo("140.00");
    }

    @Test
    void summaryReturnsZerosWhenNothingInPeriod() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T23:59:59Z");
        when(repository.sumAmountByTypeBetween(any(), eq(from), eq(to)))
                .thenReturn(BigDecimal.ZERO);

        MasterRevenueUseCase.RevenueSummary summary = service.summary(from, to);

        assertThat(summary.net()).isEqualByComparingTo("0.00");
        assertThat(summary.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void ledgerDelegatesToRepositoryWithFilters() {
        // Mutation (NullReturnVals L59): ledger é delegação pura — deve devolver
        // a página do repositório (não null).
        UUID merchantId = UUID.randomUUID();
        when(repository.findByMerchantId(eq(merchantId), eq(MasterLedgerEntryType.ADMIN_RECOVERY),
                eq(null), eq(null), eq(0), eq(20)))
                .thenReturn(PageResult.of(List.of(), 0, 20, 0));

        PageResult<MasterLedgerEntry> result = service.ledger(merchantId,
                MasterLedgerEntryType.ADMIN_RECOVERY, null, null, 0, 20);

        assertThat(result).isNotNull();
        verify(repository).findByMerchantId(any(), eq(MasterLedgerEntryType.ADMIN_RECOVERY),
                eq(null), eq(null), eq(0), eq(20));
    }
}
