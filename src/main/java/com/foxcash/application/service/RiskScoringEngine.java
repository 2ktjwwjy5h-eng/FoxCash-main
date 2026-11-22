package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.risk.MerchantRiskStats;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantConfigProvider;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.RiskProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Risk Scoring Engine (Spec 128 — Gap 2): a única ação automática de risco
 * aprovada pelo usuário. Quando o MED (refunded + chargebacks ÷ paid) da janela
 * de 30 dias ultrapassa o limiar configurado, **reduz o rate limit** do lojista
 * (`rateLimitRps`/`rateLimitRpm`); quando o MED normaliza (histerese), **restaura**.
 *
 * <p><b>Sem bloqueio de conta</b> (decisão do usuário 2026-08-16): nem
 * {@code deactivate} nem {@code suspend} — verticais de produtos digitais têm
 * chargeback recorrente; o chargeback alimenta o MED e a ação é só o rate limit
 * (reversível). <b>Override manual vence</b>: humano (master) tocou config/
 * status → {@code risk_auto_managed} → o engine não age (nem reduz nem restaura).
 *
 * <p><b>Fail-safes</b>: kill-switch global {@code foxcash.risk.auto.enabled};
 * nunca age com 0 pagos na janela (MED = 0); só age em lojista {@code ACTIVE};
 * piso de rate limit ({@code floor-rps}/{@code floor-rpm}) — a redução nunca
 * fica abaixo. <b>Idempotente</b>: a redução ocorre só na transição "sem
 * override → reduzido" (sem efeito catraca); a restauração só na transição
 * "com override do engine → sem".
 *
 * <p>Auditoria na trilha da plataforma (Spec 41 — {@code PlatformActivityRecorder}
 * com actor SYSTEM) + métrica {@code foxcash_risk_auto_actions_total{action}}.
 * Todas as escritas reusam o lock pessimista (SEC-19 —
 * {@code MerchantConfigProvider.findWithConfigForUpdate}).
 */
@Service
public class RiskScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringEngine.class);

    /** Janela de avaliação do MED (dias). */
    private static final int MED_WINDOW_DAYS = 30;

    private final MerchantConfigProvider configProvider;
    private final TransactionRepository transactionRepository;
    private final ChargebackRepository chargebackRepository;
    private final RiskProperties riskProperties;
    private final PlatformActivityRecorder activityRecorder;
    private final MeterRegistry meterRegistry;

    public RiskScoringEngine(MerchantConfigProvider configProvider,
                             TransactionRepository transactionRepository,
                             ChargebackRepository chargebackRepository,
                             RiskProperties riskProperties,
                             PlatformActivityRecorder activityRecorder,
                             MeterRegistry meterRegistry) {
        this.configProvider = configProvider;
        this.transactionRepository = transactionRepository;
        this.chargebackRepository = chargebackRepository;
        this.riskProperties = riskProperties;
        this.activityRecorder = activityRecorder;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Avalia UM lojista (chamado pelo {@link RiskMonitoringService} no loop do
     * batch do job diário — mesmo ShedLock, ponto de extensão natural).
     */
    public void evaluate(Merchant merchant) {
        RiskProperties.RiskAutoProperties auto = riskProperties.getAuto();
        if (!auto.isEnabled()) {
            return; // kill-switch global
        }
        if (merchant.riskAutoManaged()) {
            // Visibilidade: quantos lojistas estão sob controle humano.
            counter("skipped_manual").increment();
            return;
        }
        if (merchant.status() != MerchantStatus.ACTIVE) {
            return;
        }

        Instant now = Instant.now();
        Instant since = now.minus(MED_WINDOW_DAYS, ChronoUnit.DAYS);
        long approved = transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                merchant.id(), TransactionStatus.APPROVED, since, now);
        long refunded = transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                merchant.id(), TransactionStatus.REFUNDED, since, now);
        long chargebacks = chargebackRepository.countByMerchantIdAndCreatedAtAfter(
                merchant.id(), since);
        MerchantRiskStats stats = new MerchantRiskStats(approved, refunded, chargebacks);
        if (stats.paidCount() == 0) {
            // Fail-safe: sem vendas PAGAS recentes (aprovadas + estornadas) → sem
            // ação (falso positivo). Uma estornada JÁ foi paga — entra no denominador.
            return;
        }
        BigDecimal med = stats.medPercent();

        boolean hasOverride = merchant.config().rateLimitRps() != null
                || merchant.config().rateLimitRpm() != null;
        if (med.compareTo(auto.getMedThresholdPercent()) > 0 && !hasOverride) {
            reduce(merchant.id(), med);          // dispara
        } else if (med.compareTo(auto.getMedRecoverPercent()) <= 0 && hasOverride) {
            restore(merchant.id(), med);         // normalizou (override era do engine)
        }
        // senão: idempotente enquanto MED alto (não re-reduz) / sem override p/ restaurar.
    }

    /** MED alto e sem override manual → seta o override reduzido (piso garantido). */
    private void reduce(UUID merchantId, BigDecimal med) {
        // SEC-19: o merchant do batch pode estar stale — releitura com lock e
        // re-checagem dos gates antes de qualquer escrita.
        configProvider.findWithConfigForUpdate(merchantId).ifPresent(merchant -> {
            RiskProperties.RiskAutoProperties auto = riskProperties.getAuto();
            if (merchant.riskAutoManaged() || merchant.status() != MerchantStatus.ACTIVE) {
                return;
            }
            if (merchant.config().rateLimitRps() != null || merchant.config().rateLimitRpm() != null) {
                return; // alguém (re)definiu override entre o batch e o lock — não toca
            }
            int newRps = Math.max(auto.getFloorRps(), reduced(auto.getDefaultRps(), auto));
            int newRpm = Math.max(auto.getFloorRpm(), reduced(auto.getDefaultRpm(), auto));
            MerchantConfig current = merchant.config();
            MerchantConfig next = withRateLimit(current, newRps, newRpm);
            merchant.updateConfig(next);
            configProvider.save(merchant);
            activityRecorder.record(PlatformActorType.SYSTEM, null,
                    PlatformAction.RISK_AUTO_RATE_LIMIT_REDUCED,
                    "MERCHANT", merchantId, merchantId,
                    Map.of("medPercent", med,
                            "reason", "MED 30d acima do limiar automatico (Spec 128)",
                            "before", rateLimitMap(current), "after", rateLimitMap(next)));
            counter("reduced").increment();
            log.info("RiskScoringEngine: rate limit reduzido do lojista {} (MED {}%): rps {} rpm {}",
                    merchantId, med, newRps, newRpm);
        });
    }

    /** MED normalizado → limpa os overrides (volta ao teto global). */
    private void restore(UUID merchantId, BigDecimal med) {
        configProvider.findWithConfigForUpdate(merchantId).ifPresent(merchant -> {
            if (merchant.riskAutoManaged()) {
                return;
            }
            if (merchant.config().rateLimitRps() == null && merchant.config().rateLimitRpm() == null) {
                return; // já está sem override — nada a restaurar
            }
            MerchantConfig current = merchant.config();
            MerchantConfig next = withRateLimit(current, null, null);
            merchant.updateConfig(next);
            configProvider.save(merchant);
            activityRecorder.record(PlatformActorType.SYSTEM, null,
                    PlatformAction.RISK_AUTO_RATE_LIMIT_RESTORED,
                    "MERCHANT", merchantId, merchantId,
                    Map.of("medPercent", med,
                            "reason", "MED 30d normalizado — restauracao do rate limit (Spec 128)",
                            "before", rateLimitMap(current), "after", rateLimitMap(next)));
            counter("restored").increment();
            log.info("RiskScoringEngine: rate limit restaurado do lojista {} (MED {}%)",
                    merchantId, med);
        });
    }

    /**
     * Referência reduzida — arredonda para BAIXO (a redução nunca aumenta o
     * teto além do alvo; fail-safe de mitigação). O chamador aplica o piso.
     */
    private static int reduced(int reference, RiskProperties.RiskAutoProperties auto) {
        double factor = 1 - auto.getRateLimitReductionPercent().doubleValue() / 100.0;
        return (int) Math.floor(reference * factor);
    }

    private static MerchantConfig withRateLimit(MerchantConfig current, Integer rps, Integer rpm) {
        return new MerchantConfig(
                current.pixFeeType(), current.pixFeeFixed(), current.pixFeePercent(), current.pixFeeThreshold(),
                current.withdrawalFee(), current.pixMin(), current.pixMax(),
                current.withdrawalMin(), current.withdrawalMax(),
                rps, rpm, current.apiBlocked(), current.honeypot());
    }

    private static Map<String, Object> rateLimitMap(MerchantConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rateLimitRps", config.rateLimitRps());
        map.put("rateLimitRpm", config.rateLimitRpm());
        return map;
    }

    private io.micrometer.core.instrument.Counter counter(String action) {
        return meterRegistry.counter("foxcash_risk_auto_actions_total", "action", action);
    }
}
