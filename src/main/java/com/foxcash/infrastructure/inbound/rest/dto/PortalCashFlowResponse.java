package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantWalletUseCase;

import java.math.BigDecimal;

/**
 * Fluxo de caixa do lojista no periodo (Spec 22): entradas (volume aprovado)
 * vs saidas (saques COMPLETED, Spec 14). {@code net} pode ser negativo quando
 * as saidas superam as entradas.
 */
public record PortalCashFlowResponse(
        BigDecimal inflows,
        BigDecimal outflows,
        BigDecimal net) {

    public static PortalCashFlowResponse from(MerchantWalletUseCase.CashFlow cashFlow) {
        return new PortalCashFlowResponse(
                cashFlow.inflows().amount(),
                cashFlow.outflows().amount(),
                cashFlow.net());
    }
}
