package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.master.MasterEarnings;

import java.math.BigDecimal;

/**
 * Response de ganhos de um lojista (dashboard master, Spec 15).
 */
public record MasterEarningsResponse(
        BigDecimal walletBalance,
        BigDecimal approvedVolume,
        BigDecimal totalWithdrawn,
        boolean withdrawalsBlocked) {

    public static MasterEarningsResponse from(MasterEarnings earnings) {
        return new MasterEarningsResponse(
                earnings.walletBalance().amount(),
                earnings.approvedVolume().amount(),
                earnings.totalWithdrawn().amount(),
                earnings.withdrawalsBlocked());
    }
}
