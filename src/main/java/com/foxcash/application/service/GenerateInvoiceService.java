package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.GenerateInvoiceUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.service.InvoiceCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicacao: gera a fatura mensal de um lojista.
 *
 * <p>Regras do modelo fixo FoxCash:
 * <ul>
 *   <li>R$ 0,50 por transacao APROVADA no periodo</li>
 *   <li>R$ 69,95 de mensalidade</li>
 *   <li>Vencimento: 5 dias apos o fim do mes (dueDate)</li>
 * </ul>
 */
@Service
public class GenerateInvoiceService implements GenerateInvoiceUseCase {

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceCalculator invoiceCalculator;
    private final WebhookEventPublisher webhookEventPublisher;

    public GenerateInvoiceService(MerchantRepository merchantRepository,
                                  TransactionRepository transactionRepository,
                                  InvoiceRepository invoiceRepository,
                                  InvoiceCalculator invoiceCalculator,
                                  WebhookEventPublisher webhookEventPublisher) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceCalculator = invoiceCalculator;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    @Override
    @Transactional
    public Invoice generateForPeriod(UUID merchantId, YearMonth period) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        BillingPeriod billingPeriod = BillingPeriod.of(period);

        // Idempotencia: se a fatura do periodo ja existe, retorna a existente
        return invoiceRepository.findByMerchantIdAndPeriod(merchantId, billingPeriod)
                .orElseGet(() -> {
                    Instant from = period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant to = period.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                    // Auditoria 2026-08-05: count via SQL (antes carregava todas
                    // as transacoes aprovadas em memoria apenas para contar).
                    int approvedCount = (int) transactionRepository
                            .countByMerchantIdAndStatusAndCreatedAtBetween(
                                    merchantId, TransactionStatus.APPROVED, from, to);

                    LocalDate dueDate = period.atEndOfMonth().plusDays(5);
                    Invoice invoice = invoiceCalculator.draft(
                            merchantId, billingPeriod, approvedCount, merchant.pricingPlan(),
                            merchant.config(), dueDate);
                    Invoice saved = invoiceRepository.save(invoice);

                    // Outbox (Spec 7/35): notificar o lojista que a fatura foi gerada.
                    webhookEventPublisher.publish(merchantId, WebhookEventType.INVOICE_GENERATED,
                            Map.of("invoiceId", saved.id(), "period", period.toString(),
                                    "totalAmount", saved.totalAmount().amount(),
                                    "dueDate", saved.dueDate().toString()));
                    return saved;
                });
    }
}
