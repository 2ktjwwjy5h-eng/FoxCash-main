package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.InvoiceMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.InvoiceJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port InvoiceRepository.
 */
@Repository
public class InvoicePersistenceAdapter implements InvoiceRepository {

    private final InvoiceJpaRepository jpaRepository;

    public InvoicePersistenceAdapter(InvoiceJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Invoice save(Invoice invoice) {
        return InvoiceMapper.toDomain(jpaRepository.save(InvoiceMapper.toEntity(invoice)));
    }

    @Override
    public Optional<Invoice> findById(UUID invoiceId) {
        return jpaRepository.findById(invoiceId).map(InvoiceMapper::toDomain);
    }

    @Override
    public Optional<Invoice> findByMerchantIdAndPeriod(UUID merchantId, BillingPeriod period) {
        return jpaRepository.findByMerchantIdAndBillingPeriod(merchantId, period.yearMonth().toString())
                .map(InvoiceMapper::toDomain);
    }

    @Override
    public List<Invoice> findByMerchantId(UUID merchantId, int limit) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, limit))
                .stream()
                .map(InvoiceMapper::toDomain)
                .toList();
    }

    @Override
    public List<Invoice> findUnpaidDueOnOrBefore(LocalDate dueDate) {
        return jpaRepository.findUnpaidDueOnOrBefore(
                        List.of(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE), dueDate)
                .stream()
                .map(InvoiceMapper::toDomain)
                .toList();
    }
}
