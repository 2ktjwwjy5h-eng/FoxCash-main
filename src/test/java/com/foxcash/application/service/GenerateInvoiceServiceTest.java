package com.foxcash.application.service;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.service.InvoiceCalculator;
import com.foxcash.domain.service.PricingCalculator;
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
 * Testes do GenerateInvoiceService (Spec 7: notificacao de fatura gerada no outbox).
 */
class GenerateInvoiceServiceTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 7);

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);
    private final InvoiceCalculator invoiceCalculator = new InvoiceCalculator(new PricingCalculator());
    private final GenerateInvoiceService service = new GenerateInvoiceService(
            merchantRepository, transactionRepository, invoiceRepository, invoiceCalculator, webhookEventPublisher);

    private Merchant merchant() {
        return Merchant.register("Loja A", "loja@example.com", PricingPlan.fixed());
    }

    @Test
    void generatesNewInvoiceAndRecordsOutboxNotification() {
        Merchant merchant = merchant();
        when(merchantRepository.findById(merchant.id())).thenReturn(Optional.of(merchant));
        when(invoiceRepository.findByMerchantIdAndPeriod(merchant.id(), BillingPeriod.of(PERIOD)))
                .thenReturn(Optional.empty());
        when(transactionRepository.findApprovedByMerchantIdBetween(eq(merchant.id()), any(), any()))
                .thenReturn(List.of());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        Invoice invoice = service.generateForPeriod(merchant.id(), PERIOD);

        assertEquals(PERIOD, invoice.period().yearMonth());
        verify(webhookEventPublisher).publish(eq(merchant.id()),
                eq(WebhookEventType.INVOICE_GENERATED), any());
    }

    @Test
    void doesNotRecordNotificationWhenInvoiceAlreadyExists() {
        Merchant merchant = merchant();
        Invoice existing = mock(Invoice.class);
        when(merchantRepository.findById(merchant.id())).thenReturn(Optional.of(merchant));
        when(invoiceRepository.findByMerchantIdAndPeriod(merchant.id(), BillingPeriod.of(PERIOD)))
                .thenReturn(Optional.of(existing));

        Invoice result = service.generateForPeriod(merchant.id(), PERIOD);

        assertEquals(existing, result);
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void throwsMerchantNotFoundForUnknownMerchant() {
        UUID unknown = UUID.randomUUID();
        when(merchantRepository.findById(unknown)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.foxcash.domain.exception.MerchantNotFoundException.class,
                () -> service.generateForPeriod(unknown, PERIOD));
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }
}
