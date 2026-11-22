package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.master.MerchantPanelMerchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.BotLeadJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.BotLeadJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.ChargebackJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.PlanJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MerchantPanelPersistenceAdapter} (Spec 97, T4.2) — as
 * agregacoes delegam aos metodos existentes dos repositorios (COUNT/SUM/
 * COALESCE), o {@code lastProvider} usa a ultima cobranca (limit 1) e a
 * resolucao de comprador/produto usa {@code bot_leads}/{@code plans}.
 */
class MerchantPanelPersistenceAdapterTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private final PixTransactionJpaRepository transactionRepository = mock(PixTransactionJpaRepository.class);
    private final ChargebackJpaRepository chargebackRepository = mock(ChargebackJpaRepository.class);
    private final MerchantJpaRepository merchantRepository = mock(MerchantJpaRepository.class);
    private final BotLeadJpaRepository botLeadRepository = mock(BotLeadJpaRepository.class);
    private final PlanJpaRepository planRepository = mock(PlanJpaRepository.class);

    private MerchantPanelPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MerchantPanelPersistenceAdapter(transactionRepository, chargebackRepository,
                merchantRepository, botLeadRepository, planRepository);
    }

    @Test
    void countsDelegateToRepositoriesByStatus() {
        when(transactionRepository.countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.APPROVED))
                .thenReturn(710L);
        when(transactionRepository.countByMerchantId(MERCHANT_ID)).thenReturn(1254L);
        when(transactionRepository.countByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.REFUNDED))
                .thenReturn(57L);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(5L);

        assertThat(adapter.countPaid(MERCHANT_ID)).isEqualTo(710L);
        assertThat(adapter.countCreated(MERCHANT_ID)).isEqualTo(1254L);
        assertThat(adapter.countRefunded(MERCHANT_ID)).isEqualTo(57L);
        assertThat(adapter.countChargebacks(MERCHANT_ID)).isEqualTo(5L);
    }

    @Test
    void sumApprovedAmountDelegatesWithCoalesce() {
        when(transactionRepository.sumAmountByMerchantIdAndStatus(MERCHANT_ID, TransactionStatus.APPROVED))
                .thenReturn(new BigDecimal("6567.46"));

        assertThat(adapter.sumApprovedAmount(MERCHANT_ID)).isEqualByComparingTo("6567.46");
    }

    @Test
    void lastProviderReturnsFirstOfLastCharge() {
        when(transactionRepository.findLastProviderByMerchantId(eq(MERCHANT_ID), any(Pageable.class)))
                .thenReturn(List.of("suitpay", "mock"));

        assertThat(adapter.lastProvider(MERCHANT_ID)).contains("suitpay");
    }

    @Test
    void lastProviderIsEmptyWithoutCharges() {
        when(transactionRepository.findLastProviderByMerchantId(eq(MERCHANT_ID), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(adapter.lastProvider(MERCHANT_ID)).isEmpty();
    }

    @Test
    void buyerNamePrefersNameOverUsername() {
        BotLeadJpaEntity lead = new BotLeadJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                "user-1", NOW, NOW, null, null, null, null, null,
                "João da Silva", "joaosilva", "j@x.com", null, "NOVO", "BOT", NOW);
        when(botLeadRepository.findByBotIdAndPlatformUserId(lead.getBotId(), "user-1"))
                .thenReturn(Optional.of(lead));

        assertThat(adapter.buyerName(lead.getBotId(), "user-1")).contains("João da Silva");
    }

    @Test
    void buyerNameFallsBackToUsername() {
        BotLeadJpaEntity lead = new BotLeadJpaEntity(UUID.randomUUID(), UUID.randomUUID(),
                "user-2", NOW, NOW, null, null, null, null, null,
                null, "joaosilva", null, null, "NOVO", "BOT", NOW);
        when(botLeadRepository.findByBotIdAndPlatformUserId(lead.getBotId(), "user-2"))
                .thenReturn(Optional.of(lead));

        assertThat(adapter.buyerName(lead.getBotId(), "user-2")).contains("joaosilva");
    }

    @Test
    void planNameResolvesFromPlanRepository() {
        UUID planId = UUID.randomUUID();
        PlanJpaEntity plan = new PlanJpaEntity(planId, UUID.randomUUID(), "VIP Semanal",
                "desc", new BigDecimal("4.89"), null, null, true, true, NOW, NOW);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        assertThat(adapter.planName(planId)).contains("VIP Semanal");
    }

    @Test
    void merchantWithFeesMapsEntityIncludingPhone() {
        MerchantJpaEntity entity = new MerchantJpaEntity(MERCHANT_ID, "Ramon Gontijo", "r@x.com",
                "52998224725", "doc-hash", "key-hash", MerchantStatus.ACTIVE,
                new BigDecimal("0.50"), new BigDecimal("69.95"), new BigDecimal("1234.56"),
                false, NOW);
        entity.setPhone("+55 11 99999-9999");
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(entity));

        Optional<MerchantPanelMerchant> result = adapter.merchantWithFees(MERCHANT_ID);

        assertThat(result).isPresent();
        MerchantPanelMerchant merchant = result.get();
        assertThat(merchant.name()).isEqualTo("Ramon Gontijo");
        assertThat(merchant.phone()).isEqualTo("+55 11 99999-9999");
        assertThat(merchant.document()).isEqualTo("52998224725");
        assertThat(merchant.walletBalance()).isEqualTo(Money.of("1234.56"));
        assertThat(merchant.transactionFee()).isEqualTo(Money.of("0.50"));
    }

    @Test
    void merchantWithFeesEmptyWhenMerchantMissing() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThat(adapter.merchantWithFees(MERCHANT_ID)).isEmpty();
    }
}
