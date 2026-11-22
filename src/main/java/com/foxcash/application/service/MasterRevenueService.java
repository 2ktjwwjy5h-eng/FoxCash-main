package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.in.MasterRevenueUseCase;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consultas de receita/perda da plataforma (Spec 34) — somente-leitura sobre o
 * {@code master_ledger}. {@code summary} soma as entries por tipo no período
 * (SQL SUM/COALESCE) e calcula o líquido (entradas − perdas); {@code ledger}
 * retorna as movimentações paginadas com filtros. Período vazio (from/to nulos)
 * = todo o histórico.
 */
@Service
public class MasterRevenueService implements MasterRevenueUseCase {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final MasterLedgerRepository repository;

    public MasterRevenueService(MasterLedgerRepository repository) {
        this.repository = repository;
    }

    @Override
    public RevenueSummary summary(Instant from, Instant to) {
        Instant start = from != null ? from : MIN;
        Instant end = to != null ? to : MAX;
        BigDecimal transactionFees = repository.sumAmountByTypeBetween(
                MasterLedgerEntryType.TRANSACTION_FEE, start, end);
        BigDecimal monthlyFees = repository.sumAmountByTypeBetween(
                MasterLedgerEntryType.MONTHLY_FEE, start, end);
        BigDecimal recoveries = repository.sumAmountByTypeBetween(
                MasterLedgerEntryType.RECEIVABLE_RECOVERY, start, end);
        BigDecimal adminRecoveries = repository.sumAmountByTypeBetween(
                MasterLedgerEntryType.ADMIN_RECOVERY, start, end);
        // Spec 88: taxa de saque — Σ WITHDRAWAL_FEE − Σ WITHDRAWAL_FEE_REVERSAL
        // (reversões de saques que falharam, padrão SEC-29; nunca negativa na
        // prática, pois a reversão só ocorre para taxa previamente registrada).
        BigDecimal withdrawalFees = repository.sumAmountByTypeBetween(
                        MasterLedgerEntryType.WITHDRAWAL_FEE, start, end)
                .subtract(repository.sumAmountByTypeBetween(
                        MasterLedgerEntryType.WITHDRAWAL_FEE_REVERSAL, start, end));
        BigDecimal losses = repository.sumAmountByTypeBetween(
                        MasterLedgerEntryType.CHARGEBACK_LOSS, start, end)
                // SEC-29 (revisão de segurança 2026-08-11): saque administrativo
                // que falhou devolve o valor ao lojista — a receita registrada
                // (ADMIN_RECOVERY) é revertida e a reversão conta como perda.
                .add(repository.sumAmountByTypeBetween(
                        MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL, start, end));

        BigDecimal total = transactionFees.add(monthlyFees)
                .add(recoveries).add(adminRecoveries).add(withdrawalFees).add(losses);
        BigDecimal net = transactionFees.add(monthlyFees)
                .add(recoveries).add(adminRecoveries).add(withdrawalFees).subtract(losses);
        return new RevenueSummary(transactionFees, monthlyFees, recoveries,
                adminRecoveries, withdrawalFees, losses, total, net);
    }

    @Override
    public PageResult<MasterLedgerEntry> ledger(UUID merchantId, MasterLedgerEntryType type,
                                                Instant from, Instant to, int page, int size) {
        return repository.findByMerchantId(merchantId, type, from, to, page, size);
    }
}
