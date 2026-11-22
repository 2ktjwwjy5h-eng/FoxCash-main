package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.AutoWithdrawConfigService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Resposta da config de saque automatico (Spec 70) — {@code GET/PUT
 * /api/v1/portal/auto-withdraw}. {@code threshold} em {@code BigDecimal}
 * (escala 2 — serializacao padrao de {@code Money.amount()}).
 */
public record AutoWithdrawConfigResponse(
        boolean enabled,
        BigDecimal threshold,
        UUID destinationId) {

    public static AutoWithdrawConfigResponse from(AutoWithdrawConfigService.ConfigView view) {
        return new AutoWithdrawConfigResponse(
                view.enabled(), view.threshold().amount(), view.destinationId());
    }
}
