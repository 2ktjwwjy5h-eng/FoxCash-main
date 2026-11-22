package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterEarnings;
import com.foxcash.domain.model.master.MasterFees;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MasterDashboardPort;
import com.foxcash.infrastructure.outbound.persistence.entity.InvoiceJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.PixTransactionJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.PixTransactionMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.InvoiceJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.WithdrawalJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador JPA do port {@link MasterDashboardPort} (Spec 15). Filtros
 * dinamicos via Specifications; somas calculadas no PostgreSQL (SUM/COALESCE).
 */
@Repository
public class MasterDashboardPersistenceAdapter implements MasterDashboardPort {

    private final PixTransactionJpaRepository transactionRepository;
    private final MerchantJpaRepository merchantRepository;
    private final InvoiceJpaRepository invoiceRepository;
    private final WithdrawalJpaRepository withdrawalRepository;

    public MasterDashboardPersistenceAdapter(PixTransactionJpaRepository transactionRepository,
                                             MerchantJpaRepository merchantRepository,
                                             InvoiceJpaRepository invoiceRepository,
                                             WithdrawalJpaRepository withdrawalRepository) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.invoiceRepository = invoiceRepository;
        this.withdrawalRepository = withdrawalRepository;
    }

    @Override
    public PageResult<PixTransaction> findTransactions(MasterTransactionSearchQuery query) {
        Page<PixTransactionJpaEntity> page = transactionRepository.findAll(
                transactionSpec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<PixTransaction> items = page.getContent().stream()
                .map(PixTransactionMapper::toDomain)
                .toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public MasterEarnings earnings(java.util.UUID merchantId) {
        MerchantJpaEntity merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        BigDecimal approvedVolume = transactionRepository.sumAmountByMerchantIdAndStatus(
                merchantId, TransactionStatus.APPROVED);
        BigDecimal totalWithdrawn = withdrawalRepository.sumCompletedAmountByMerchantId(merchantId);
        return new MasterEarnings(
                Money.of(merchant.getWalletBalance()),
                Money.of(approvedVolume),
                Money.of(totalWithdrawn),
                merchant.isWithdrawalsBlocked());
    }

    @Override
    public MasterFees fees(java.util.UUID merchantId, BillingPeriod period) {
        InvoiceJpaEntity invoice = invoiceRepository
                .findByMerchantIdAndBillingPeriod(merchantId, period.yearMonth().toString())
                .orElse(null);
        if (invoice == null) {
            return new MasterFees(0, Money.of("0.00"), Money.of("0.00"), Money.of("0.00"));
        }
        Money transactionFees = Money.of(invoice.getTransactionFeesTotal());
        Money monthlyFee = Money.of(invoice.getMonthlyFee());
        return new MasterFees(
                invoice.getTransactionCount(),
                transactionFees,
                monthlyFee,
                transactionFees.add(monthlyFee));
    }

    private Specification<PixTransactionJpaEntity> transactionSpec(MasterTransactionSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
