package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantWalletUseCase;

import java.math.BigDecimal;

/**
 * Resposta do saldo do lojista no portal (Spec 22).
 * {@code saldoCalculadoPelaApi} e sempre {@code true} — o valor vem do fluxo
 * oficial (Spec 14), nunca de estimativa local do frontend.
 */
public record PortalWalletBalanceResponse(
        BigDecimal walletBalance,
        boolean withdrawalsBlocked,
        boolean saldoCalculadoPelaApi) {

    public static PortalWalletBalanceResponse from(MerchantWalletUseCase.Balance balance) {
        return new PortalWalletBalanceResponse(
                balance.walletBalance().amount(),
                balance.withdrawalsBlocked(),
                balance.saldoCalculadoPelaApi());
    }
}
