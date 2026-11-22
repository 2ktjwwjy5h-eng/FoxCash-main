package com.foxcash.application.service;

import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.port.in.QueryInvoiceUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: consultas de faturas.
 */
@Service
@Transactional(readOnly = true)
public class QueryInvoiceService implements QueryInvoiceUseCase {

    private final InvoiceRepository invoiceRepository;

    public QueryInvoiceService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @Override
    public Optional<Invoice> findById(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId);
    }

    @Override
    public List<Invoice> findByMerchantId(UUID merchantId, int limit) {
        return invoiceRepository.findByMerchantId(merchantId, limit);
    }
}
