package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.master.MerchantPanelMerchant;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MerchantPanelRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.BotLeadJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.BotLeadJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.ChargebackJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantPanelRepository} (Spec 97) — contagens/
 * somas em SQL (padrao M8/Spec 30), reusando os metodos agregados ja
 * existentes dos repositorios. O MED% e derivado no service (nunca aqui).
 *
 * <p>Resolucao do comprador/produto por transacao (N+1 por item — pagina de 20
 * do console de auditoria, padrao documentado no design D5): lead do CRM via
 * {@code bot_leads} por {@code (bot_id, platform_user_id)} e plano via
 * {@code plans.name} por {@code plan_id}.
 */
@Repository
public class MerchantPanelPersistenceAdapter implements MerchantPanelRepository {

    private final PixTransactionJpaRepository transactionRepository;
    private final ChargebackJpaRepository chargebackRepository;
    private final MerchantJpaRepository merchantRepository;
    private final BotLeadJpaRepository botLeadRepository;
    private final PlanJpaRepository planRepository;

    public MerchantPanelPersistenceAdapter(PixTransactionJpaRepository transactionRepository,
                                           ChargebackJpaRepository chargebackRepository,
                                           MerchantJpaRepository merchantRepository,
                                           BotLeadJpaRepository botLeadRepository,
                                           PlanJpaRepository planRepository) {
        this.transactionRepository = transactionRepository;
        this.chargebackRepository = chargebackRepository;
        this.merchantRepository = merchantRepository;
        this.botLeadRepository = botLeadRepository;
        this.planRepository = planRepository;
    }

    @Override
    public long countPaid(UUID merchantId) {
        return transactionRepository.countByMerchantIdAndStatus(merchantId, TransactionStatus.APPROVED);
    }

    @Override
    public long countCreated(UUID merchantId) {
        return transactionRepository.countByMerchantId(merchantId);
    }

    @Override
    public long countRefunded(UUID merchantId) {
        return transactionRepository.countByMerchantIdAndStatus(merchantId, TransactionStatus.REFUNDED);
    }

    @Override
    public long countChargebacks(UUID merchantId) {
        return chargebackRepository.countByMerchantId(merchantId);
    }

    @Override
    public BigDecimal sumApprovedAmount(UUID merchantId) {
        return transactionRepository.sumAmountByMerchantIdAndStatus(merchantId, TransactionStatus.APPROVED);
    }

    @Override
    public Optional<String> lastProvider(UUID merchantId) {
        return transactionRepository.findLastProviderByMerchantId(merchantId, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    @Override
    public Optional<String> buyerName(UUID botId, String platformUserId) {
        return botLeadRepository.findByBotIdAndPlatformUserId(botId, platformUserId)
                .map(this::toBuyerName);
    }

    @Override
    public Optional<String> planName(UUID planId) {
        return planRepository.findById(planId).map(PlanJpaEntity::getName);
    }

    @Override
    public Optional<MerchantPanelMerchant> merchantWithFees(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(this::toPanelMerchant);
    }

    /** Nome do comprador via lead do CRM — {@code name}, fallback {@code username}. */
    private String toBuyerName(BotLeadJpaEntity lead) {
        return lead.getName() != null ? lead.getName() : lead.getUsername();
    }

    private MerchantPanelMerchant toPanelMerchant(MerchantJpaEntity entity) {
        return new MerchantPanelMerchant(
                entity.getId(),
                entity.getName(),
                entity.getEmail(),
                entity.getDocument(),
                entity.getStatus(),
                Money.of(entity.getPricingFeePerTransaction()),
                Money.of(entity.getPricingMonthlyFee()),
                Money.of(entity.getWalletBalance()),
                entity.getPhone(),
                entity.getCreatedAt(),
                MerchantMapper.toConfig(entity));
    }
}
