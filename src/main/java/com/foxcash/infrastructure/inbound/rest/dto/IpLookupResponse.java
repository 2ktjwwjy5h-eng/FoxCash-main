package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.admin.IpLookupResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response do lookup administrativo de IP (Spec 67 — {@code !ip} do bot de
 * operações). Nunca devolve o IP além do consultado nem chaves/segredos de
 * webhook; o bot mascara o IP ao exibir.
 */
public record IpLookupResponse(String ip, List<MerchantMatchResponse> matches) {

    public record MerchantMatchResponse(
            UUID merchantId,
            String name,
            String email,
            String status,
            long totalSales,
            BigDecimal averageTicket,
            Instant lastSaleAt,
            long sales7d,
            BigDecimal revenue7d,
            BotMatchResponse bot) {

        public record BotMatchResponse(
                UUID storeId,
                String storeName,
                int activePlans,
                int activeWebhooks,
                String link) {
        }
    }

    public static IpLookupResponse from(IpLookupResult result) {
        List<MerchantMatchResponse> matches = result.matches().stream()
                .map(m -> new MerchantMatchResponse(
                        m.merchantId(),
                        m.name(),
                        m.email(),
                        m.status(),
                        m.totalSales(),
                        m.averageTicket().amount(),
                        m.lastSaleAt(),
                        m.sales7d(),
                        m.revenue7d().amount(),
                        m.bot() != null
                                ? new MerchantMatchResponse.BotMatchResponse(
                                m.bot().storeId(),
                                m.bot().storeName(),
                                m.bot().activePlans(),
                                m.bot().activeWebhooks(),
                                m.bot().link())
                                : null))
                .toList();
        return new IpLookupResponse(result.ip(), matches);
    }
}
