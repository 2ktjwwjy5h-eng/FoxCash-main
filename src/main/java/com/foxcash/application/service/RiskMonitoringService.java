package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.RiskMonitoringUseCase;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.RiskProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Aplicacao: monitoramento de risco (Spec 16, ADR-008) + antifraude (Spec 19).
 *
 * <p>Calcula os indicadores de risco de todos os lojistas a partir dos dados
 * existentes (transacoes + chargebacks registrados) e grava um snapshot por
 * lojista com o score 0-100. Desde a Spec 19 o chargeback tem fonte de dados
 * (registros de chargeback) e o sistema pode SUSPENDER o lojista
 * automaticamente quando o chargeback atinge o limiar duro — configuravel e
 * desligavel via {@code foxcash.risk.auto-suspend-enabled} (a decisao manual
 * do master continua valida via /master/merchants/{id}/suspend|release).
 */
@Service
public class RiskMonitoringService implements RiskMonitoringUseCase {

    /** Tamanho do batch de varredura (auditoria 2026-08-05/M-A3). */
    private static final int PAGE_SIZE = 200;

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final ChargebackRepository chargebackRepository;
    private final RiskRepository riskRepository;
    private final RiskProperties riskProperties;
    private final RiskScoringEngine riskScoringEngine;

    public RiskMonitoringService(MerchantRepository merchantRepository,
                                 TransactionRepository transactionRepository,
                                 ChargebackRepository chargebackRepository,
                                 RiskRepository riskRepository,
                                 RiskProperties riskProperties,
                                 RiskScoringEngine riskScoringEngine) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.chargebackRepository = chargebackRepository;
        this.riskRepository = riskRepository;
        this.riskProperties = riskProperties;
        this.riskScoringEngine = riskScoringEngine;
    }

    @Override
    @Transactional
    public int computeForAllMerchants() {
        // Auditoria 2026-08-05 (M-A3): processa em páginas (batch) — antes
        // carregava a tabela inteira em memória (findAll) e fazia N queries.
        int saved = 0;
        int page = 0;
        PageResult<Merchant> batch;
        do {
            batch = merchantRepository.findAll(page, PAGE_SIZE);
            for (Merchant merchant : batch.items()) {
                MerchantRiskSnapshot snapshot = computeFor(merchant.id());
                riskRepository.save(snapshot);
                maybeAutoSuspend(merchant, snapshot);
                // Spec 128: após o snapshot, o Risk Scoring Engine avalia o
                // lojista (MED 30d → reduzir/restaurar rate limit). Mesmo job,
                // mesmo lock — ponto de extensão natural.
                riskScoringEngine.evaluate(merchant);
                saved++;
            }
            page++;
        } while (!batch.items().isEmpty() && page < batch.totalPages());
        return saved;
    }

    private MerchantRiskSnapshot computeFor(java.util.UUID merchantId) {
        long total = transactionRepository.countByMerchantId(merchantId);
        long approved = transactionRepository.countByMerchantIdAndStatus(merchantId, TransactionStatus.APPROVED);
        long refunded = transactionRepository.countByMerchantIdAndStatus(merchantId, TransactionStatus.REFUNDED);
        Money approvedVolume = Money.of(transactionRepository.sumAmountByMerchantIdAndStatus(
                merchantId, TransactionStatus.APPROVED));

        // Spec 19: chargeback agora tem fonte de dados (registros de chargeback).
        long chargebacks = chargebackRepository.countByMerchantId(merchantId);

        BigDecimal refundRate = ratePercent(refunded, total);
        BigDecimal chargebackRate = ratePercent(chargebacks, total);
        boolean refundAlert = refundRate.compareTo(riskProperties.getRefundAlertPercent()) > 0;
        boolean chargebackAlert = chargebackRate.compareTo(riskProperties.getChargebackAlertPercent()) > 0;

        // Denuncias seguem sem fonte modelada (chegam fora do fluxo Pix).
        int complaints = 0;
        boolean complaintsAlert = false;

        int riskScore = computeScore(refundAlert, chargebackAlert, chargebackRate, complaintsAlert);

        return new MerchantRiskSnapshot(
                merchantId,
                Instant.now(),
                approvedVolume,
                total,
                approved,
                refunded,
                refundRate,
                chargebackRate,
                complaints,
                refundAlert,
                chargebackAlert,
                complaintsAlert,
                riskScore);
    }

    /**
     * Suspende automaticamente o lojista ACTIVE com chargeback >= limiar duro
     * (Spec 19). Desligavel via {@code foxcash.risk.auto-suspend-enabled=false}.
     */
    private void maybeAutoSuspend(Merchant merchant, MerchantRiskSnapshot snapshot) {
        if (!riskProperties.isAutoSuspendEnabled()) {
            return;
        }
        if (merchant.status() != MerchantStatus.ACTIVE) {
            return;
        }
        if (snapshot.chargebackRatePercent().compareTo(riskProperties.getChargebackSuspendPercent()) >= 0) {
            // SEC-19 (revisão de segurança 2026-08-11): o merchant veio de um
            // findById (batch por página) — a snapshot pode estar stale. O save
            // grava TODOS os campos (incluindo wallet_balance) e sobrescreveria
            // um débito/crédito concorrente (saque/webhook/chargeback, que usam
            // FOR UPDATE). Releitura FRESCA com lock antes de suspender.
            merchantRepository.findByIdForUpdate(merchant.id()).ifPresent(fresh -> {
                if (fresh.status() == MerchantStatus.ACTIVE) {
                    fresh.suspend();
                    merchantRepository.save(fresh);
                }
            });
        }
    }

    /** Score 0-100 (Spec 19): soma ponderada dos sinais, cap 100 — 100 = maior risco. */
    private int computeScore(boolean refundAlert, boolean chargebackAlert,
                             BigDecimal chargebackRate, boolean complaintsAlert) {
        int score = 0;
        if (refundAlert) {
            score += 25;
        }
        if (chargebackAlert) {
            score += 40;
        }
        if (chargebackRate.compareTo(riskProperties.getChargebackSuspendPercent()) >= 0) {
            score += 35;
        }
        if (complaintsAlert) {
            score += 15;
        }
        return Math.min(score, 100);
    }

    private BigDecimal ratePercent(long part, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_EVEN);
    }
}
