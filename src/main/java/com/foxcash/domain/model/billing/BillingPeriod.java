package com.foxcash.domain.model.billing;

import java.time.YearMonth;

/**
 * Periodo de faturamento (ex.: 2025-08).
 */
public record BillingPeriod(YearMonth yearMonth) {

    public BillingPeriod {
        if (yearMonth == null) {
            throw new IllegalArgumentException("yearMonth must not be null");
        }
    }

    public static BillingPeriod of(YearMonth yearMonth) {
        return new BillingPeriod(yearMonth);
    }
}
