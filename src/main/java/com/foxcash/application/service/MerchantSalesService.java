package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantSalesUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.service.PricingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aplicacao: vendas/operação comercial do lojista no portal (Spec 25) — somente
 * leitura. Listagem paginada com filtros (status + janela de datas), métricas
 * de topo (volume 30d, concluídas, pendentes, conversão) e detalhe da venda
 * (payload Pix somente quando {@code PENDING}).
 *
 * <p>{@code netAmount = grossAmount − taxa} — mesma fonte das faturas (Spec 8):
 * {@code merchant.pricingPlan()} via {@link PricingCalculator}. Sem PII do
 * comprador (Spec 19): {@code buyerIdHash} opaco ou {@code null}.
 */
@Service
@Transactional(readOnly = true)
public class MerchantSalesService implements MerchantSalesUseCase {

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final PricingCalculator pricingCalculator;
    private final Clock clock;

    public MerchantSalesService(TransactionRepository transactionRepository,
                                MerchantRepository merchantRepository,
                                PricingCalculator pricingCalculator,
                                Clock clock) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.pricingCalculator = pricingCalculator;
        this.clock = clock;
    }

    @Override
    public PageResult<Sale> list(UUID merchantId, UUID profileId, TransactionStatus status,
                                 Instant from, Instant to, int page, int size) {
        PageResult<PixTransaction> result = transactionRepository.searchSales(
                merchantId, profileId, status, from, to, page, size);
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        List<Sale> items = result.items().stream()
                .map(tx -> new Sale(tx.id(), tx.buyerIdHash(), tx.amount(),
                        netAmount(merchant, tx), tx.status(), tx.createdAt()))
                .toList();
        return PageResult.of(items, result.page(), result.size(), result.totalElements());
    }

    /** Net por transação com o override da config (Spec 98 — D4.1). */
    private Money netAmount(Merchant merchant, PixTransaction tx) {
        Money fee = pricingCalculator.transactionFee(merchant.pricingPlan(), merchant.config(), tx.amount());
        return tx.amount().subtract(fee);
    }

    @Override
    public Metrics metrics(UUID merchantId, UUID profileId, int days) {
        int clamped = Math.min(Math.max(days, 1), 90);
        Instant to = clock.instant();
        Instant from = to.minusSeconds((long) clamped * 24 * 60 * 60);

        // Spec 124: profileId presente → só o perfil (header inválido → vazio).
        BigDecimal volume = Money.normalize(profileId != null
                ? transactionRepository.sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.APPROVED, from, to)
                : transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to));
        long completed = profileId != null
                ? transactionRepository.countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.APPROVED, from, to)
                : transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to);
        long pending = profileId != null
                ? transactionRepository.countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.PENDING, from, to)
                : transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.PENDING, from, to);
        long created = profileId != null
                ? transactionRepository.countByMerchantIdAndProfileIdAndCreatedAtBetween(
                        merchantId, profileId, from, to)
                : transactionRepository.countByMerchantIdAndCreatedAtBetween(merchantId, from, to);

        BigDecimal conversion = created == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(completed)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(created), 2, RoundingMode.HALF_UP);
        return new Metrics(volume, completed, pending, conversion);
    }

    @Override
    public SaleDetail detail(UUID merchantId, UUID profileId, UUID transactionId) {
        PixTransaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        if (!tx.merchantId().equals(merchantId)) {
            throw new MerchantAccessDeniedException();
        }
        // Spec 124: com perfil ativo, transação de outro perfil não vaza.
        if (profileId != null && !profileId.equals(tx.profileId())) {
            throw new MerchantAccessDeniedException();
        }
        Money fee = transactionFee(merchantId, tx.amount());
        boolean pending = tx.status() == TransactionStatus.PENDING;
        return new SaleDetail(
                tx.id(), tx.buyerIdHash(), tx.amount(), tx.amount().subtract(fee),
                tx.status(), tx.createdAt(), tx.paidAt(),
                pending ? tx.description() : null,
                pending ? tx.pixKey() : null,
                pending ? tx.pixQrCode() : null,
                pending ? tx.pixCopyPaste() : null,
                pending ? tx.endToEndId() : null);
    }

    /** Taxa por transação do lojista (mesma fonte das faturas, Spec 8) com o
     * override da config fina (Spec 98 — D4.1: FIXED/PERCENT/threshold). */
    private Money transactionFee(UUID merchantId, Money amount) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        return pricingCalculator.transactionFee(merchant.pricingPlan(), merchant.config(), amount);
    }
}
