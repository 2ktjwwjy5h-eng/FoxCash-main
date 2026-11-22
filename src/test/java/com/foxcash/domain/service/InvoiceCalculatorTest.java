package com.foxcash.domain.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Testes da construcao de faturas mensais.
 */
class InvoiceCalculatorTest {

    private final InvoiceCalculator calculator = new InvoiceCalculator(new PricingCalculator());

    @Test
    void draft_buildsInvoiceWithFixedPricing() {
        UUID merchantId = UUID.randomUUID();
        BillingPeriod period = BillingPeriod.of(YearMonth.of(2025, 8));
        LocalDate dueDate = LocalDate.of(2025, 9, 5);

        Invoice invoice = calculator.draft(merchantId, period, 120, PricingPlan.fixed(),
                MerchantConfig.defaults(), dueDate);

        assertNotNull(invoice.id());
        assertEquals(merchantId, invoice.merchantId());
        assertEquals(period, invoice.period());
        assertEquals(120, invoice.transactionCount());
        // 120 * 0,50 = 60,00
        assertEquals(Money.of("60.00"), invoice.transactionFeesTotal());
        assertEquals(Money.of("69.95"), invoice.monthlyFee());
        assertEquals(Money.of("129.95"), invoice.totalAmount());
        assertEquals(InvoiceStatus.PENDING, invoice.status());
        assertEquals(dueDate, invoice.dueDate());
    }
}
