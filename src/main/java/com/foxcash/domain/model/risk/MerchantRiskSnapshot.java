package com.foxcash.domain.model.risk;

import com.foxcash.domain.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot de risco de um lojista (Spec 16, ADR-008) — indicadores
 * calculados sobre os dados existentes (transações) num dado momento.
 *
 * <p>{@code complaintsCount} e 0 nesta versao (nao ha fonte de dados modelada
 * — denuncia chega fora do fluxo Pix); {@code chargebackRatePercent} agora e
 * alimentado pelos chargebacks registrados (Spec 19). {@code riskScore} e o
 * score agregado 0-100 derivado das regras (Spec 19).
 */
public class MerchantRiskSnapshot {

    private final UUID merchantId;
    private final Instant computedAt;
    private final Money approvedVolume;
    private final long totalTransactions;
    private final long approvedTransactions;
    private final long refundedTransactions;
    private final BigDecimal refundRatePercent;
    private final BigDecimal chargebackRatePercent;
    private final int complaintsCount;
    private final boolean refundAlert;
    private final boolean chargebackAlert;
    private final boolean complaintsAlert;
    private final int riskScore;

    public MerchantRiskSnapshot(UUID merchantId, Instant computedAt, Money approvedVolume,
                                long totalTransactions, long approvedTransactions,
                                long refundedTransactions, BigDecimal refundRatePercent,
                                BigDecimal chargebackRatePercent, int complaintsCount,
                                boolean refundAlert, boolean chargebackAlert, boolean complaintsAlert,
                                int riskScore) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt must not be null");
        this.approvedVolume = Objects.requireNonNull(approvedVolume, "approvedVolume must not be null");
        this.totalTransactions = totalTransactions;
        this.approvedTransactions = approvedTransactions;
        this.refundedTransactions = refundedTransactions;
        this.refundRatePercent = refundRatePercent;
        this.chargebackRatePercent = chargebackRatePercent;
        this.complaintsCount = complaintsCount;
        this.refundAlert = refundAlert;
        this.chargebackAlert = chargebackAlert;
        this.complaintsAlert = complaintsAlert;
        this.riskScore = riskScore;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public Money approvedVolume() {
        return approvedVolume;
    }

    public long totalTransactions() {
        return totalTransactions;
    }

    public long approvedTransactions() {
        return approvedTransactions;
    }

    public long refundedTransactions() {
        return refundedTransactions;
    }

    public BigDecimal refundRatePercent() {
        return refundRatePercent;
    }

    public BigDecimal chargebackRatePercent() {
        return chargebackRatePercent;
    }

    public int complaintsCount() {
        return complaintsCount;
    }

    public boolean refundAlert() {
        return refundAlert;
    }

    public boolean chargebackAlert() {
        return chargebackAlert;
    }

    public boolean complaintsAlert() {
        return complaintsAlert;
    }

    /** Score de risco agregado 0-100 (Spec 19) — 100 = maior risco. */
    public int riskScore() {
        return riskScore;
    }
}
