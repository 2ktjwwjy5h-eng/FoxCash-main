package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.port.out.AutoWithdrawConfigRepository;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.AutoWithdrawProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Configuracao do saque automatico por limiar de saldo (Spec 70) — portal do
 * lojista ({@code GET/PUT /api/v1/portal/auto-withdraw}). Sempre o proprio
 * lojista (merchantId do principal; nunca do body — impossivel IDOR).
 *
 * <p>{@code GET} nunca devolve 404: config ausente → {@code {enabled: false,
 * threshold: default da plataforma, destinationId: null}} (settings com default
 * autoritativo no backend — o frontend nao duplica o limiar, D11.1).
 * {@code PUT} faz upsert na MESMA transacao da auditoria
 * {@code AUTO_WITHDRAW_SETTINGS_UPDATED} (Spec 41).
 */
@Service
public class AutoWithdrawConfigService {

    /** Visao da config devolvida ao portal (threshold em {@link Money}). */
    public record ConfigView(boolean enabled, Money threshold, UUID destinationId) {
    }

    private final AutoWithdrawConfigRepository configRepository;
    private final WithdrawalDestinationRepository destinationRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final AutoWithdrawProperties properties;

    public AutoWithdrawConfigService(AutoWithdrawConfigRepository configRepository,
                                     WithdrawalDestinationRepository destinationRepository,
                                     PlatformActivityRecorder activityRecorder,
                                     AutoWithdrawProperties properties) {
        this.configRepository = configRepository;
        this.destinationRepository = destinationRepository;
        this.activityRecorder = activityRecorder;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ConfigView get(UUID merchantId) {
        return configRepository.findByMerchantId(merchantId)
                .map(c -> new ConfigView(c.enabled(), c.threshold(), c.destinationId()))
                .orElseGet(() -> new ConfigView(false,
                        Money.of(properties.getDefaultThreshold()), null));
    }

    /**
     * Upsert da config (mesma transacao da auditoria). Regras:
     * {@code threshold} obrigatorio e {@code > 0} e {@code >= min-threshold}
     * (400 {@code invalid_argument}); {@code enabled=true} exige
     * {@code destinationId} do proprio lojista e ATIVO (400/404/409);
     * {@code enabled=false} nao exige destino — se ausente, MANTEM o anterior
     * ({@code destination_id NOT NULL}: "mantem o anterior"; o job ignora
     * configs desabilitadas). Linha ausente + {@code enabled=false} → estado
     * ja e "desabilitado" (nada a persistir; o GET devolve defaults).
     */
    @Transactional
    public ConfigView update(UUID merchantId, boolean enabled, BigDecimal threshold,
                             UUID destinationId) {
        Money thresholdMoney = validateThreshold(threshold);
        var existing = configRepository.findByMerchantId(merchantId);

        // Destino efetivo: enabled=false sem destinationId mantém o anterior
        // (coluna NOT NULL; re-enable vira 1-clique com o destino salvo).
        UUID effectiveDestinationId = destinationId;
        if (!enabled && effectiveDestinationId == null && existing.isPresent()) {
            effectiveDestinationId = existing.get().destinationId();
        }
        final UUID destId = effectiveDestinationId; // effectively final (lambda)

        if (enabled) {
            if (destId == null) {
                throw new IllegalArgumentException(
                        "destinationId e obrigatorio quando o saque automatico esta habilitado");
            }
            // Mesmas regras do saque manual (Spec 32): destino do proprio lojista
            // (404 sem revelar existencia) e ATIVO (409).
            WithdrawalDestination destination = destinationRepository
                    .findByIdAndMerchantId(destId, merchantId)
                    .orElseThrow(() -> new DestinationNotFoundException(destId));
            if (!destination.active()) {
                throw new DestinationInactiveException(destId);
            }
        }

        AutoWithdrawConfig saved;
        if (existing.isPresent()) {
            saved = configRepository.save(
                    existing.get().update(enabled, thresholdMoney, destId));
        } else if (enabled) {
            saved = configRepository.save(
                    AutoWithdrawConfig.enable(merchantId, thresholdMoney, destId));
        } else {
            // Linha ausente = desabilitado: nada a persistir (o GET devolve
            // defaults); sem auditoria de no-op.
            return new ConfigView(false, thresholdMoney, null);
        }
        // Spec 41: trilha unificada — config alterada (sem PII; apenas
        // enabled/threshold/destinationId). Mesma transacao (rollback = sem evento).
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.AUTO_WITHDRAW_SETTINGS_UPDATED, "AUTO_WITHDRAW",
                saved.id(), merchantId, Map.of(
                        "enabled", enabled,
                        "threshold", thresholdMoney.amount(),
                        "destinationId", destId == null ? null : destId.toString()));
        return new ConfigView(saved.enabled(), saved.threshold(), saved.destinationId());
    }

    /**
     * Valida o threshold vindo do PUT: obrigatorio (400 {@code invalid_argument}),
     * {@code Money.of} rejeita mais de 2 casas decimais (M9, Spec 26), {@code > 0}
     * explicito (Money permite negativo) e {@code >= min-threshold} da plataforma.
     */
    private Money validateThreshold(BigDecimal threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold e obrigatorio");
        }
        Money money = Money.of(threshold);
        if (money.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("threshold deve ser maior que zero");
        }
        if (money.amount().compareTo(properties.getMinThreshold()) < 0) {
            throw new IllegalArgumentException(
                    "threshold abaixo do minimo de " + properties.getMinThreshold());
        }
        return money;
    }
}
