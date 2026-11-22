package com.foxcash.application.service;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.GenerateInvoiceUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do use case de geracao automatica de faturas (Spec 6 - scheduler).
 */
class GenerateInvoicesForPeriodServiceTest {

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final GenerateInvoiceUseCase generateInvoiceUseCase = mock(GenerateInvoiceUseCase.class);
    private final GenerateInvoicesForPeriodService service =
            new GenerateInvoicesForPeriodService(merchantRepository, invoiceRepository, generateInvoiceUseCase);

    private static final YearMonth PERIOD = YearMonth.of(2026, 7);

    private Merchant activeMerchant(String name) {
        return Merchant.register(name, "loja@example.com", PricingPlan.fixed());
    }

    @Test
    void generatesInvoiceForEveryActiveMerchantWithoutInvoice() {
        Merchant m1 = activeMerchant("Loja A");
        Merchant m2 = activeMerchant("Loja B");
        when(merchantRepository.findAllActive()).thenReturn(List.of(m1, m2));
        when(invoiceRepository.findByMerchantIdAndPeriod(any(UUID.class), any(BillingPeriod.class)))
                .thenReturn(Optional.empty());

        int generated = service.generateForPeriod(PERIOD);

        assertEquals(2, generated);
        verify(generateInvoiceUseCase).generateForPeriod(m1.id(), PERIOD);
        verify(generateInvoiceUseCase).generateForPeriod(m2.id(), PERIOD);
    }

    @Test
    void skipsMerchantsThatAlreadyHaveInvoiceForThePeriod() {
        Merchant m1 = activeMerchant("Loja A");
        Merchant m2 = activeMerchant("Loja B");
        Invoice existing = mock(Invoice.class);
        when(merchantRepository.findAllActive()).thenReturn(List.of(m1, m2));
        when(invoiceRepository.findByMerchantIdAndPeriod(eq(m1.id()), any(BillingPeriod.class)))
                .thenReturn(Optional.of(existing));
        when(invoiceRepository.findByMerchantIdAndPeriod(eq(m2.id()), any(BillingPeriod.class)))
                .thenReturn(Optional.empty());

        int generated = service.generateForPeriod(PERIOD);

        assertEquals(1, generated);
        verify(generateInvoiceUseCase, never()).generateForPeriod(m1.id(), PERIOD);
        verify(generateInvoiceUseCase).generateForPeriod(m2.id(), PERIOD);
    }

    @Test
    void returnsZeroWhenNoActiveMerchants() {
        when(merchantRepository.findAllActive()).thenReturn(List.of());

        int generated = service.generateForPeriod(PERIOD);

        assertEquals(0, generated);
        verify(generateInvoiceUseCase, never()).generateForPeriod(any(UUID.class), any(YearMonth.class));
    }
}
