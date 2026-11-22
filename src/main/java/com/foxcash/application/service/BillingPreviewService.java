package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.BillingPreviewUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.service.PricingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Aplicacao: previsao da fatura mensal de um lojista (sem persistir).
 * Regra: totalTransactions * feePorTransacao + feeFixa.
 */
@Service
@Transactional(readOnly = true)
public class BillingPreviewService implements BillingPreviewUseCase {

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final PricingCalculator pricingCalculator;

    public BillingPreviewService(MerchantRepository merchantRepository,
                                 TransactionRepository transactionRepository,
                                 PricingCalculator pricingCalculator) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.pricingCalculator = pricingCalculator;
    }

    @Override
    public Preview preview(UUID merchantId, YearMonth month) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Auditoria 2026-08-05: count via SQL (antes carregava as transacoes
        // aprovadas em memoria apenas para contar).
        int totalTransactions = (int) transactionRepository
                .countByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to);

        Money variableFee = pricingCalculator.totalTransactionFees(
                merchant.pricingPlan(), merchant.config(), totalTransactions);
        Money fixedFee = merchant.pricingPlan().monthlyFee();
        Money totalDue = variableFee.add(fixedFee);

        // Spec 98 (D4.1): a taxa exibida reflete o override FIXED quando presente.
        Money perTxFee = pricingCalculator.transactionFee(
                merchant.pricingPlan(), merchant.config(), null);
        String calculation = totalTransactions + " * " + perTxFee.amount()
                + " + " + fixedFee.amount() + " = " + totalDue.amount();

        return new Preview(totalTransactions, variableFee, fixedFee, totalDue, calculation);
    }
}
