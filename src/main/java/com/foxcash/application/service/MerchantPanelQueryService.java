package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelMerchant;
import com.foxcash.domain.model.master.MerchantPanelSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelTransactionQuery;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.MerchantPanelQueryUseCase;
import com.foxcash.domain.port.out.AdminDashboardPort;
import com.foxcash.domain.port.out.MasterDashboardPort;
import com.foxcash.domain.port.out.MerchantPanelRepository;
import com.foxcash.domain.port.out.StoreRepository;
import com.foxcash.domain.service.PricingCalculator;
import com.foxcash.infrastructure.config.MasterPanelProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.inbound.rest.DocumentMasker;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelItem;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelTransaction;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantStoreResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Aplicacao: painel de usuarios do Master Console (Spec 97) — somente-leitura.
 * Compoe dados existentes (merchants, pix_transactions, chargebacks, bot_leads,
 * plans, stores, wallet) em respostas ricas. O MED% e derivado ao vivo
 * (nunca do snapshot diario {@code merchant_risk}); o gateway e o provider da
 * ultima cobranca (Spec 91).
 *
 * <p>N+1 por item na listagem e nas transacoes (pagina de 20, console de
 * auditoria de baixa frequencia — padrao documentado no design D5).
 */
@Service
@Transactional(readOnly = true)
public class MerchantPanelQueryService implements MerchantPanelQueryUseCase {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MerchantPanelRepository panelRepository;
    private final AdminDashboardPort adminDashboard;
    private final MasterDashboardPort masterDashboard;
    private final StoreRepository storeRepository;
    private final com.foxcash.domain.port.out.DeviceFingerprintRepository fingerprintRepository;
    private final PricingCalculator pricingCalculator;
    private final PricingProperties pricingProperties;
    private final MasterPanelProperties panelProperties;

    public MerchantPanelQueryService(MerchantPanelRepository panelRepository,
                                     AdminDashboardPort adminDashboard,
                                     MasterDashboardPort masterDashboard,
                                     StoreRepository storeRepository,
                                     com.foxcash.domain.port.out.DeviceFingerprintRepository fingerprintRepository,
                                     PricingCalculator pricingCalculator,
                                     PricingProperties pricingProperties,
                                     MasterPanelProperties panelProperties) {
        this.panelRepository = panelRepository;
        this.adminDashboard = adminDashboard;
        this.masterDashboard = masterDashboard;
        this.storeRepository = storeRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.pricingCalculator = pricingCalculator;
        this.pricingProperties = pricingProperties;
        this.panelProperties = panelProperties;
    }

    @Override
    public PageResult<MerchantPanelItem> searchPanel(MerchantPanelSearchQuery query) {
        MerchantSearchQuery merchantQuery = new MerchantSearchQuery(
                query.status(), query.query(), query.from(), query.to(), query.page(), query.size());
        PageResult<Merchant> page = adminDashboard.findMerchants(merchantQuery);
        List<MerchantPanelItem> items = page.items().stream()
                .map(this::toPanelItem)
                .filter(item -> matchesRisk(item, query.risk()))
                .toList();
        return PageResult.of(items, page.page(), page.size(), page.totalElements());
    }

    @Override
    public MerchantPanelDetail panelDetail(UUID merchantId) {
        MerchantPanelMerchant merchant = requireMerchant(merchantId);
        long paid = panelRepository.countPaid(merchantId);
        long created = panelRepository.countCreated(merchantId);
        long refunded = panelRepository.countRefunded(merchantId);
        long chargebacks = panelRepository.countChargebacks(merchantId);
        BigDecimal gross = panelRepository.sumApprovedAmount(merchantId);
        String gateway = panelRepository.lastProvider(merchantId).orElse(null);
        long stores = storeRepository.existsByMerchantId(merchantId) ? 1 : 0;

        MerchantPanelDetail.PanelKpis kpis = new MerchantPanelDetail.PanelKpis(
                merchant.walletBalance().amount(),
                Money.normalize(gross),
                paid,
                conversionRate(paid, created),
                stores);

        MerchantPanelDetail.PanelAccount account = new MerchantPanelDetail.PanelAccount(
                merchant.name(),
                merchant.email(),
                merchant.phone(),
                DocumentMasker.mask(merchant.document()),
                gateway,
                merchant.createdAt());

        MerchantPanelDetail.PanelFeesAndLimits feesAndLimits = new MerchantPanelDetail.PanelFeesAndLimits(
                effectiveTransactionFee(merchant),
                merchant.transactionFee().amount(),
                effectiveWithdrawalFee(merchant),
                medPercent(paid, refunded + chargebacks),
                refunded + chargebacks,
                merchant.config().pixMin() == null ? null : merchant.config().pixMin().amount(),
                merchant.config().pixMax() == null ? null : merchant.config().pixMax().amount(),
                merchant.config().withdrawalMin() == null ? null : merchant.config().withdrawalMin().amount(),
                merchant.config().withdrawalMax() == null ? null : merchant.config().withdrawalMax().amount());

        // Spec 141: sinal de risco — quantos lojistas DISTINTOS o dispositivo
        // do lojista tocou (0 = sem fingerprint, 1 = exclusivo, N>1 = risco).
        int fingerprintDistinctMerchants = fingerprintRepository.maxDistinctMerchantsOf(merchantId);

        return MerchantPanelDetail.of(kpis, account, feesAndLimits, fingerprintDistinctMerchants);
    }

    @Override
    public PageResult<MerchantPanelTransaction> transactions(UUID merchantId,
                                                             MerchantPanelTransactionQuery query) {
        MerchantPanelMerchant merchant = requireMerchant(merchantId);
        com.foxcash.domain.model.master.MasterTransactionSearchQuery txQuery =
                new com.foxcash.domain.model.master.MasterTransactionSearchQuery(
                        merchantId, query.status(), query.from(), query.to(), query.page(), query.size());
        PageResult<PixTransaction> page = masterDashboard.findTransactions(txQuery);
        List<MerchantPanelTransaction> items = page.items().stream()
                .map(tx -> toPanelTransaction(tx, toPricingPlan(merchant), merchant.config()))
                .toList();
        return PageResult.of(items, page.page(), page.size(), page.totalElements());
    }

    @Override
    public List<MerchantStoreResponse> stores(UUID merchantId) {
        requireMerchant(merchantId);
        return storeRepository.findByMerchantId(merchantId)
                .stream().map(MerchantStoreResponse::from).toList();
    }

    // ---- helpers ----

    private MerchantPanelItem toPanelItem(Merchant merchant) {
        UUID id = merchant.id();
        long paid = panelRepository.countPaid(id);
        long created = panelRepository.countCreated(id);
        long refunded = panelRepository.countRefunded(id);
        long chargebacks = panelRepository.countChargebacks(id);
        long occurrences = refunded + chargebacks;
        String gateway = panelRepository.lastProvider(id).orElse(null);
        return new MerchantPanelItem(
                id,
                merchant.name(),
                merchant.email(),
                merchant.status(),
                effectiveTransactionFee(merchant),
                merchant.pricingPlan().monthlyFee().amount(),
                effectiveWithdrawalFee(merchant),
                conversionRate(paid, created),
                paid,
                medPercent(paid, occurrences),
                occurrences,
                gateway,
                merchant.createdAt());
    }

    /** Filtro {@code risk} pos-paginacao: faixas do {@code medPercent} (D6). */
    private boolean matchesRisk(MerchantPanelItem item, MerchantPanelSearchQuery.RiskLevel risk) {
        if (risk == null) {
            return true;
        }
        BigDecimal med = item.medPercent();
        BigDecimal low = panelProperties.getRiskThresholds().getLowPercent();
        BigDecimal mid = panelProperties.getRiskThresholds().getMidPercent();
        return switch (risk) {
            case LOW -> med.compareTo(low) <= 0;
            case MEDIUM -> med.compareTo(low) > 0 && med.compareTo(mid) <= 0;
            case HIGH -> med.compareTo(mid) > 0;
        };
    }

    private MerchantPanelTransaction toPanelTransaction(PixTransaction tx, PricingPlan plan,
                                                        MerchantConfig config) {
        String buyerName = tx.botId() != null && tx.platformUserId() != null
                ? panelRepository.buyerName(tx.botId(), tx.platformUserId()).orElse(null)
                : null;
        String productName = tx.planId() != null
                ? panelRepository.planName(tx.planId()).orElse(null)
                : null;
        BigDecimal amount = tx.amount().amount();
        // Spec 98 (D4.1): netAmount por transação reflete o override FIXED/PERCENT.
        Money fee = pricingCalculator.transactionFee(plan, config, tx.amount());
        return new MerchantPanelTransaction(
                tx.id(),
                amount,
                tx.status(),
                buyerName,
                tx.buyerIdHash(),
                productName,
                tx.externalReference(),
                fee.amount(),
                Money.normalize(amount.subtract(fee.amount())),
                tx.createdAt());
    }

    private MerchantPanelMerchant requireMerchant(UUID merchantId) {
        return panelRepository.merchantWithFees(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    }

    /** Taxa de saque efetiva (Spec 98): override do lojista quando presente; senao global (Spec 88). */
    private BigDecimal effectiveWithdrawalFee(Merchant merchant) {
        if (merchant.config().withdrawalFee() != null) {
            return merchant.config().withdrawalFee().amount();
        }
        return pricingProperties.getWithdrawalFee();
    }

    /** Taxa de saque efetiva (Spec 98): override do lojista quando presente; senao global (Spec 88). */
    private BigDecimal effectiveWithdrawalFee(MerchantPanelMerchant merchant) {
        if (merchant.config().withdrawalFee() != null) {
            return merchant.config().withdrawalFee().amount();
        }
        return pricingProperties.getWithdrawalFee();
    }

    /** Taxa por transação exibida no painel (Spec 98 — D4.1): FIXED → fixa;
     * PERCENT → a fixa (até o threshold); sem override → plano. */
    private BigDecimal effectiveTransactionFee(Merchant merchant) {
        return effectiveTransactionFee(merchant.pricingPlan(), merchant.config());
    }

    private BigDecimal effectiveTransactionFee(MerchantPanelMerchant merchant) {
        return effectiveTransactionFee(toPricingPlan(merchant), merchant.config());
    }

    private BigDecimal effectiveTransactionFee(PricingPlan plan, MerchantConfig config) {
        Money fee = pricingCalculator.transactionFee(plan, config, null);
        return fee.amount();
    }

    private PricingPlan toPricingPlan(MerchantPanelMerchant merchant) {
        return new PricingPlan(merchant.transactionFee(), merchant.monthlyFee());
    }

    /** Conversao: pagos ÷ criados × 100 (0 sem criadas). */
    private BigDecimal conversionRate(long paid, long created) {
        return created == 0
                ? BigDecimal.ZERO
                : percent(paid, created);
    }

    /** MED: (refunded + chargebacks) ÷ pagos × 100 (0 sem pagos). */
    private BigDecimal medPercent(long paid, long occurrences) {
        return paid == 0
                ? BigDecimal.ZERO
                : percent(occurrences, paid);
    }

    private BigDecimal percent(long part, long total) {
        return BigDecimal.valueOf(part)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_EVEN);
    }
}
