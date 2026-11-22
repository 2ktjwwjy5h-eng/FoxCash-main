package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.BotMetrics;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aplicação: métricas por bot (Spec 75/D4/D7) — {@code leads} (COUNT de leads),
 * {@code vendas} e {@code receita} (transações {@code PAID} NÃO estornadas —
 * fonte oficial, Specs 23/29; estorno/chargeback desfaz a venda) e
 * {@code conversao} (vendas/leads). Escopo: portal por bot/loja (ROLE_MERCHANT_PORTAL)
 * e espelho master (ROLE_MASTER, todos os lojistas).
 *
 * <p>O funil {@code bot_funnel_events} NUNCA é somado como dinheiro — a métrica
 * de venda lê {@code pix_transactions} (status {@code APPROVED} = PAID).
 */
@Service
public class BotMetricsService {

    /** Período default das métricas (Spec 75/D7): 30 dias. */
    static final int DEFAULT_WINDOW_DAYS = 30;

    private final StoreBotRepository botRepository;
    private final MerchantRepository merchantRepository;
    private final BotLeadRepository leadRepository;
    private final TransactionRepository transactionRepository;

    public BotMetricsService(StoreBotRepository botRepository,
                             MerchantRepository merchantRepository,
                             BotLeadRepository leadRepository,
                             TransactionRepository transactionRepository) {
        this.botRepository = botRepository;
        this.merchantRepository = merchantRepository;
        this.leadRepository = leadRepository;
        this.transactionRepository = transactionRepository;
    }

    /** Métricas de um bot no período (portal — 404 cross-lojista no controller). */
    @Transactional(readOnly = true)
    public BotMetrics summarize(UUID botId, Instant from, Instant to) {
        Instant[] window = normalizeWindow(from, to);
        long leads = leadRepository.countByBotIdAndFirstSeenAtBetween(botId, window[0], window[1]);
        return metricsFor(botId, leads, window[0], window[1]);
    }

    /**
     * Métricas agregadas de um lojista no período (portal) — lista por bot
     * (NÃO removidos) + total. Vendas/receita contam apenas transações
     * atribuídas a bots ({@code bot_id NOT NULL}).
     */
    @Transactional(readOnly = true)
    public MerchantBotMetrics summarizeMerchant(UUID merchantId, Instant from, Instant to) {
        return summarizeMerchant(merchantId, null, from, to);
    }

    /**
     * Métricas agregadas de um lojista no período (portal), escopadas por
     * bot-set (Spec 124 — filtro rígido por perfil). {@code botIds} null →
     * todos os bots do lojista (regressão — sem header); presente → só os bots
     * do perfil (vazio → sem dados — header inválido nunca vaza).
     */
    @Transactional(readOnly = true)
    public MerchantBotMetrics summarizeMerchant(UUID merchantId, List<UUID> botIds,
                                                Instant from, Instant to) {
        Instant[] window = normalizeWindow(from, to);
        List<StoreBot> fleet = botRepository.findByMerchantId(merchantId);
        List<StoreBot> scoped = botIds == null ? fleet : fleet.stream()
                .filter(b -> botIds.contains(b.id())).toList();
        List<BotMetricsView> bots = scoped.stream()
                .map(bot -> new BotMetricsView(bot.id(), merchantId, null,
                        summarize(bot.id(), window[0], window[1])))
                .toList();
        long leads;
        long vendas;
        BigDecimal receitaSum;
        if (botIds == null) {
            leads = leadRepository.countByMerchantIdAndFirstSeenAtBetween(
                    merchantId, window[0], window[1]);
            vendas = transactionRepository.countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                    merchantId, TransactionStatus.APPROVED, window[0], window[1]);
            receitaSum = transactionRepository.sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                    merchantId, TransactionStatus.APPROVED, window[0], window[1]);
        } else {
            // Leads do bot-set (as métricas por bot da Spec 75 somam os leads dos
            // bots do perfil; vendas/receita = transações APPROVED atribuídas a
            // esses bots — fonte oficial Specs 23/29).
            leads = leadRepository.countGroupedByBotId(botIds, window[0], window[1])
                    .values().stream().mapToLong(Long::longValue).sum();
            vendas = transactionRepository.countByBotIdInAndStatusAndCreatedAtBetween(
                    botIds, TransactionStatus.APPROVED, window[0], window[1]);
            receitaSum = transactionRepository.sumAmountByBotIdInAndStatusAndCreatedAtBetween(
                    botIds, TransactionStatus.APPROVED, window[0], window[1]);
        }
        return new MerchantBotMetrics(bots, BotMetrics.of(leads, vendas, Money.of(receitaSum)));
    }

    /**
     * Métricas de todos os lojistas no período (espelho master, RBAC Spec 69) —
     * lista por bot (com nome do lojista) + total. {@code merchantId} opcional
     * (filtro por lojista).
     */
    @Transactional(readOnly = true)
    public MerchantBotMetrics summarizeAll(Instant from, Instant to, UUID merchantId) {
        Instant[] window = normalizeWindow(from, to);
        List<StoreBot> bots = merchantId == null
                ? botRepository.findAllNonDeleted()
                : botRepository.findByMerchantId(merchantId);
        List<BotMetricsView> views = bots.stream()
                .map(bot -> new BotMetricsView(bot.id(), bot.merchantId(),
                        merchantName(bot.merchantId()), summarize(bot.id(), window[0], window[1])))
                .toList();
        BotMetrics total = views.stream()
                .map(BotMetricsView::metrics)
                .reduce(BotMetrics.of(0, 0, Money.of("0")), BotMetrics::add);
        return new MerchantBotMetrics(views, total);
    }

    /**
     * Normaliza o período default de 30 dias (Spec 75/D7): {@code from} null →
     * {@code to - 30d}; {@code to} null → agora. Evita {@code BETWEEN} com
     * extremos nulos nas queries de contagem/soma.
     */
    private static Instant[] normalizeWindow(Instant from, Instant to) {
        Instant toInstant = to != null ? to : Instant.now();
        Instant fromInstant = from != null ? from
                : toInstant.minusSeconds(DEFAULT_WINDOW_DAYS * 86_400L);
        return new Instant[]{fromInstant, toInstant};
    }

    private BotMetrics metricsFor(UUID botId, long leads, Instant from, Instant to) {
        long vendas = transactionRepository.countByBotIdAndStatusAndCreatedAtBetween(
                botId, TransactionStatus.APPROVED, from, to);
        BigDecimal sum = transactionRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(
                botId, TransactionStatus.APPROVED, from, to);
        return BotMetrics.of(leads, vendas, Money.of(sum));
    }

    private String merchantName(UUID merchantId) {
        return merchantRepository.findById(merchantId).map(m -> m.name()).orElse(null);
    }

    /** Métricas de um bot na lista (master/portal agregado). */
    public record BotMetricsView(UUID botId, UUID merchantId, String merchantName,
                                 BotMetrics metrics) {
    }

    /** Agregado: lista por bot + total. */
    public record MerchantBotMetrics(List<BotMetricsView> bots, BotMetrics total) {
    }
}
