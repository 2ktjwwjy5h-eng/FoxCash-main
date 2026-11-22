package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantDashboardUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: dashboard do lojista no portal (Spec 21) — somente leitura.
 *
 * <p>Métricas de topo (receita, vendas, conversao PIX), serie de faturamento
 * por dia, ultimas vendas e pontos de atencao derivados do risco (Spec 16/19)
 * e do estado do lojista. "Jornada do player" = gamificacao do lojista:
 * progresso de volume aprovado frente a meta configuravel
 * ({@code foxcash.portal.journey-goal-*}).
 */
@Service
@Transactional(readOnly = true)
public class MerchantDashboardService implements MerchantDashboardUseCase {

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final RiskRepository riskRepository;
    private final PortalProperties portalProperties;

    public MerchantDashboardService(MerchantRepository merchantRepository,
                                    TransactionRepository transactionRepository,
                                    RiskRepository riskRepository,
                                    PortalProperties portalProperties) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.riskRepository = riskRepository;
        this.portalProperties = portalProperties;
    }

    @Override
    public Summary summary(UUID merchantId, UUID profileId, Instant from, Instant to) {
        // Auditoria 2026-08-05 (M3): as metricas respeitam a janela from/to —
        // antes os parametros eram aceitos mas ignorados (metricas all-time).
        // Spec 124: profileId presente → só o perfil (header inválido → vazio).
        long total = profileId != null
                ? transactionRepository.countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.APPROVED, from, to)
                : transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to);
        long created = profileId != null
                ? transactionRepository.countByMerchantIdAndProfileIdAndCreatedAtBetween(
                        merchantId, profileId, from, to)
                : transactionRepository.countByMerchantIdAndCreatedAtBetween(merchantId, from, to);
        BigDecimal approvedVolume = profileId != null
                ? transactionRepository.sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.APPROVED, from, to)
                : transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to);

        BigDecimal conversion = created == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(total)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(created), 2, RoundingMode.HALF_UP);

        int journeyProgress = journeyProgress(approvedVolume);

        return new Summary(Money.of(approvedVolume), total, conversion, journeyProgress);
    }

    @Override
    public List<TimeSeriesPoint> revenueSeries(UUID merchantId, UUID profileId, Instant from, Instant to) {
        List<PixTransaction> approved = profileId != null
                ? transactionRepository.findApprovedByMerchantIdBetweenAndProfileId(
                        merchantId, profileId, from, to)
                : transactionRepository.findApprovedByMerchantIdBetween(merchantId, from, to);

        // Agrupa por dia (UTC) somando os valores aprovados.
        Map<LocalDate, Money> byDay = new LinkedHashMap<>();
        for (PixTransaction tx : approved) {
            LocalDate day = tx.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
            byDay.merge(day, tx.amount(), Money::add);
        }

        // Preenche todos os dias do intervalo (dias sem venda com 0) — série contínua.
        List<TimeSeriesPoint> series = new ArrayList<>();
        LocalDate cursor = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = to.atZone(ZoneOffset.UTC).toLocalDate();
        while (!cursor.isAfter(end)) {
            series.add(new TimeSeriesPoint(cursor, byDay.getOrDefault(cursor, Money.of("0.00"))));
            cursor = cursor.plusDays(1);
        }
        return series;
    }

    @Override
    public List<RecentSale> recentSales(UUID merchantId, UUID profileId, int limit) {
        // Auditoria 2026-08-05: query limitada (mais recentes primeiro) —
        // antes carregava o historico inteiro e ordenava em memoria.
        List<PixTransaction> recent = profileId != null
                ? transactionRepository.findByMerchantIdAndProfileId(merchantId, profileId, limit)
                : transactionRepository.findByMerchantId(merchantId, limit);
        return recent.stream()
                .map(tx -> new RecentSale(
                        tx.id(), tx.amount(), tx.status().name(), tx.createdAt(), tx.buyerIdHash()))
                .toList();
    }

    @Override
    public List<AttentionPoint> attentionPoints(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
        List<AttentionPoint> points = new ArrayList<>();
        if (merchant == null) {
            return points;
        }

        if (merchant.status() != MerchantStatus.ACTIVE) {
            points.add(new AttentionPoint("merchant_status", "HIGH",
                    "Loja " + merchant.status().name().toLowerCase() + " — pagamentos suspensos"));
        }
        if (merchant.withdrawalsBlocked()) {
            points.add(new AttentionPoint("withdrawals_blocked", "MEDIUM",
                    "Saques bloqueados pelo operador"));
        }

        Optional<MerchantRiskSnapshot> risk = riskRepository.latestByMerchant(merchantId);
        risk.ifPresent(snapshot -> {
            if (snapshot.chargebackAlert()) {
                points.add(new AttentionPoint("chargeback_alert", "HIGH",
                        "Taxa de chargeback acima do limiar de alerta"));
            }
            if (snapshot.refundAlert()) {
                points.add(new AttentionPoint("refund_alert", "MEDIUM",
                        "Taxa de reembolso acima de 5%"));
            }
        });
        return points;
    }

    /** Progresso da "jornada do player" (gamificacao do lojista): volume aprovado ÷ meta × 100 (cap 100). */
    private int journeyProgress(BigDecimal approvedVolume) {
        BigDecimal goal = portalProperties.getJourneyGoalAmount();
        if (goal == null || goal.signum() <= 0) {
            return 0;
        }
        BigDecimal progress = approvedVolume
                .multiply(BigDecimal.valueOf(100))
                .divide(goal, 0, RoundingMode.DOWN);
        return progress.min(BigDecimal.valueOf(100)).intValue();
    }
}
