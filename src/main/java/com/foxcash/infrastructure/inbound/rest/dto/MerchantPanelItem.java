package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.merchant.MerchantStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Item da listagem do painel de usuarios (Spec 97, D3.1) — lojista com metricas
 * de risco derivadas ao vivo ({@code medPercent}/{@code medOccurrences}),
 * conversao, Pix pagos e gateway efetivo. Tudo aditivo sob {@code ROLE_MASTER};
 * o {@code GET /api/v1/master/merchants} (Spec 71) permanece intacto.
 *
 * @param withdrawalFee taxa de saque efetiva (override da Spec 98 quando
 *                      configurado; senao o global da Spec 88)
 * @param gateway       provedor da ultima cobranca (null → UI mostra "PADRAO")
 */
public record MerchantPanelItem(
        UUID id,
        String name,
        String email,
        MerchantStatus status,
        BigDecimal transactionFee,
        BigDecimal monthlyFee,
        BigDecimal withdrawalFee,
        BigDecimal conversionRate,
        long paidPixCount,
        BigDecimal medPercent,
        long medOccurrences,
        String gateway,
        Instant createdAt) {
}
