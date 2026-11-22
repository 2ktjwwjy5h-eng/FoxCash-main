package com.foxcash.domain.model.master;

import com.foxcash.domain.model.Money;

/**
 * Ganhos de um lojista vistos pelo master (Spec 15) — somente-leitura.
 */
public record MasterEarnings(
        Money walletBalance,
        Money approvedVolume,
        Money totalWithdrawn,
        boolean withdrawalsBlocked) {
}
