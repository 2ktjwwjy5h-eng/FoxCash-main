package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response do perfil do lojista logado no portal ({@code GET /api/v1/portal/me},
 * Spec 20) — sanidade da sessao. Spec 39: nome e e-mail no lugar de
 * tradingName/legalName/documento. Spec 94: campo aditivo {@code mfaActive}
 * (contrato existente inalterado) para a UI decidir o estado da página Segurança.
 */
public record PortalMeResponse(
        UUID merchantId,
        String name,
        String email,
        MerchantStatus status,
        BigDecimal walletBalance,
        boolean withdrawalsBlocked,
        Instant createdAt,
        boolean mfaActive) {

    public static PortalMeResponse from(Merchant merchant, boolean mfaActive) {
        return new PortalMeResponse(
                merchant.id(),
                merchant.name(),
                merchant.email(),
                merchant.status(),
                merchant.walletBalance().amount(),
                merchant.withdrawalsBlocked(),
                merchant.createdAt(),
                mfaActive);
    }
}
