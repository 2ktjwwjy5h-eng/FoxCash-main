package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.BotMetrics;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link BotMetricsService} (Spec 75): métricas por bot/loja —
 * {@code leads} (COUNT), {@code vendas}/{@code receita} (transações {@code
 * APPROVED} NÃO estornadas — fonte oficial) e {@code conversao}. Período
 * default 30d; espelho master com nome do lojista; estornado não conta
 * (o filtro é {@code status = APPROVED}, então REFUNDED/CHARGEBACK ficam fora).
 */
class BotMetricsServiceTest {

    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-31T23:59:59Z");
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID OTHER_BOT = UUID.randomUUID();

    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final BotLeadRepository leadRepository = mock(BotLeadRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    private final BotMetricsService service = new BotMetricsService(
            botRepository, merchantRepository, leadRepository, transactionRepository);

    private StoreBot bot(UUID id, UUID merchantId) {
        return StoreBot.restore(id, merchantId, BotPlatform.TELEGRAM, "token",
                "hash", "wh-secret-hash", "Bot da Loja", "loja_bot", null,
                BotStatus.ACTIVE, null, null, null, FROM, FROM);
    }

    private Merchant merchant(String name) {
        return Merchant.restore(MERCHANT_ID, name, "loja@example.com", null, null,
                MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, FROM);
    }

    @Test
    void summarizeComputesLeadsVendasReceitaAndConversao() {
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(4L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(2L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(new BigDecimal("99.80"));

        BotMetrics metrics = service.summarize(BOT_ID, FROM, TO);

        assertThat(metrics.leads()).isEqualTo(4);
        assertThat(metrics.vendas()).isEqualTo(2);
        assertThat(metrics.receita().amount()).isEqualByComparingTo("99.80");
        assertThat(metrics.conversao()).isEqualTo(0.5); // 2/4
    }

    @Test
    void summarizeWithZeroLeadsHasZeroConversion() {
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(0L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(0L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(BigDecimal.ZERO);

        BotMetrics metrics = service.summarize(BOT_ID, FROM, TO);

        assertThat(metrics.conversao()).isEqualTo(0.0);
    }

    @Test
    void summarizeUsesApprovedStatusOnlySoEstornadoDoesNotCount() {
        // A métrica de venda lê SOMENTE status APPROVED (fonte oficial Specs
        // 23/29) — transação REFUNDED/CHARGEBACK não entra na contagem.
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(1L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                any(), eq(TransactionStatus.APPROVED), any(), any())).thenReturn(0L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                any(), eq(TransactionStatus.APPROVED), any(), any())).thenReturn(BigDecimal.ZERO);

        BotMetrics metrics = service.summarize(BOT_ID, FROM, TO);

        assertThat(metrics.vendas()).isZero();
        verify(transactionRepository).countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO);
    }

    @Test
    void summarizeMerchantAggregatesBotsAndTotalForMerchant() {
        StoreBot bot1 = bot(BOT_ID, MERCHANT_ID);
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(bot1));
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(3L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(1L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(new BigDecimal("49.90"));
        // Total da loja: leads/vendas agregados por MERCHANT (só bots atribuídos).
        when(leadRepository.countByMerchantIdAndFirstSeenAtBetween(MERCHANT_ID, FROM, TO)).thenReturn(3L);
        when(transactionRepository.countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(1L);
        when(transactionRepository.sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(new BigDecimal("49.90"));

        BotMetricsService.MerchantBotMetrics result = service.summarizeMerchant(MERCHANT_ID, FROM, TO);

        assertThat(result.bots()).hasSize(1);
        assertThat(result.bots().get(0).botId()).isEqualTo(BOT_ID);
        assertThat(result.total().leads()).isEqualTo(3);
        assertThat(result.total().vendas()).isEqualTo(1);
        assertThat(result.total().receita().amount()).isEqualByComparingTo("49.90");
    }

    @Test
    void summarizeAllIsMasterViewWithMerchantName() {
        StoreBot bot1 = bot(BOT_ID, MERCHANT_ID);
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(bot1));
        when(merchantRepository.findById(MERCHANT_ID))
                .thenReturn(Optional.of(merchant("Loja A LTDA")));
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(2L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(0L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(BigDecimal.ZERO);

        BotMetricsService.MerchantBotMetrics result = service.summarizeAll(FROM, TO, null);

        assertThat(result.bots()).hasSize(1);
        assertThat(result.bots().get(0).merchantName()).isEqualTo("Loja A LTDA");
        assertThat(result.total().leads()).isEqualTo(2);
    }

    @Test
    void summarizeAllFiltersByMerchantWhenProvided() {
        StoreBot bot1 = bot(BOT_ID, MERCHANT_ID);
        StoreBot bot2 = bot(OTHER_BOT, UUID.randomUUID());
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(bot1, bot2));
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(any(), any(), any())).thenReturn(0L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                any(), eq(TransactionStatus.APPROVED), any(), any())).thenReturn(0L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                any(), eq(TransactionStatus.APPROVED), any(), any())).thenReturn(BigDecimal.ZERO);

        BotMetricsService.MerchantBotMetrics result = service.summarizeAll(FROM, TO, MERCHANT_ID);

        assertThat(result.bots()).hasSize(2);
        verify(botRepository).findByMerchantId(MERCHANT_ID);
    }

    @Test
    void summarizeMerchantScopesByBotSetWhenProfileActive() {
        // Spec 124 (filtro rígido por bot-set): com botIds do perfil ativo, só
        // os bots do perfil entram na lista e os agregados somam os bots IN set.
        StoreBot bot1 = bot(BOT_ID, MERCHANT_ID);
        StoreBot bot2 = bot(OTHER_BOT, MERCHANT_ID);
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(bot1, bot2));
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(3L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(1L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(new BigDecimal("49.90"));
        when(leadRepository.countGroupedByBotId(List.of(BOT_ID), FROM, TO))
                .thenReturn(Map.of(BOT_ID, 3L));
        when(transactionRepository.countByBotIdInAndStatusAndCreatedAtBetween(
                List.of(BOT_ID), TransactionStatus.APPROVED, FROM, TO)).thenReturn(1L);
        when(transactionRepository.sumAmountByBotIdInAndStatusAndCreatedAtBetween(
                List.of(BOT_ID), TransactionStatus.APPROVED, FROM, TO)).thenReturn(new BigDecimal("49.90"));

        BotMetricsService.MerchantBotMetrics result =
                service.summarizeMerchant(MERCHANT_ID, List.of(BOT_ID), FROM, TO);

        // Só o bot do perfil aparece (bot2 fica fora — frota do outro perfil).
        assertThat(result.bots()).hasSize(1);
        assertThat(result.bots().get(0).botId()).isEqualTo(BOT_ID);
        assertThat(result.total().leads()).isEqualTo(3);
        assertThat(result.total().vendas()).isEqualTo(1);
        assertThat(result.total().receita().amount()).isEqualByComparingTo("49.90");
        verify(transactionRepository).countByBotIdInAndStatusAndCreatedAtBetween(
                List.of(BOT_ID), TransactionStatus.APPROVED, FROM, TO);
    }

    @Test
    void summarizeMerchantWithoutBotIdsIsWholeMerchantRegression() {
        // Sem header (botIds null) → comportamento original (agregado da loja).
        StoreBot bot1 = bot(BOT_ID, MERCHANT_ID);
        when(botRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(bot1));
        when(leadRepository.countByBotIdAndFirstSeenAtBetween(BOT_ID, FROM, TO)).thenReturn(2L);
        when(transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(0L);
        when(transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                BOT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(BigDecimal.ZERO);
        when(leadRepository.countByMerchantIdAndFirstSeenAtBetween(MERCHANT_ID, FROM, TO)).thenReturn(2L);
        when(transactionRepository.countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(0L);
        when(transactionRepository.sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, FROM, TO)).thenReturn(BigDecimal.ZERO);

        BotMetricsService.MerchantBotMetrics result =
                service.summarizeMerchant(MERCHANT_ID, null, FROM, TO);

        assertThat(result.bots()).hasSize(1);
        verify(transactionRepository)
                .countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                        MERCHANT_ID, TransactionStatus.APPROVED, FROM, TO);
    }
}
