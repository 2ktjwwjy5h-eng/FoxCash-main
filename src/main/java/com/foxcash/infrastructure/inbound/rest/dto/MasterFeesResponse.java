package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.master.MasterFees;

import java.math.BigDecimal;

/**
 * Response de taxas de um lojista num periodo (dashboard master, Spec 15).
 */
public record MasterFeesResponse(
        int transactionCount,
        BigDecimal transactionFees,
        BigDecimal monthlyFee,
        BigDecimal totalFees) {

    public static MasterFeesResponse from(MasterFees fees) {
        return new MasterFeesResponse(
                fees.transactionCount(),
                fees.transactionFees().amount(),
                fees.monthlyFee().amount(),
                fees.totalFees().amount());
    }
}
