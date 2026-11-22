package com.foxcash.application.service;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.GenerateInvoiceUseCase;
import com.foxcash.domain.port.in.GenerateInvoicesForPeriodUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;

/**
 * Aplicacao: geracao automatica de faturas de todos os lojistas ACTIVE para um
 * periodo (usada pelo scheduler mensal). Idempotente: lojistas que ja possuem
 * fatura do periodo sao ignorados.
 */
@Service
public class GenerateInvoicesForPeriodService implements GenerateInvoicesForPeriodUseCase {

    private final MerchantRepository merchantRepository;
    private final InvoiceRepository invoiceRepository;
    private final GenerateInvoiceUseCase generateInvoiceUseCase;

    public GenerateInvoicesForPeriodService(MerchantRepository merchantRepository,
                                            InvoiceRepository invoiceRepository,
                                            GenerateInvoiceUseCase generateInvoiceUseCase) {
        this.merchantRepository = merchantRepository;
        this.invoiceRepository = invoiceRepository;
        this.generateInvoiceUseCase = generateInvoiceUseCase;
    }

    @Override
    @Transactional
    public int generateForPeriod(YearMonth period) {
        BillingPeriod billingPeriod = BillingPeriod.of(period);
        List<Merchant> activeMerchants = merchantRepository.findAllActive();

        int generated = 0;
        for (Merchant merchant : activeMerchants) {
            if (invoiceRepository.findByMerchantIdAndPeriod(merchant.id(), billingPeriod).isPresent()) {
                continue;
            }
            generateInvoiceUseCase.generateForPeriod(merchant.id(), period);
            generated++;
        }
        return generated;
    }
}
