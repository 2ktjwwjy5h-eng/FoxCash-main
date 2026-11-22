package com.foxcash.domain.model.admin;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado do lookup administrativo de um endereço IP (Spec 67 — comando
 * {@code !ip} do bot de operações): lojistas associados ao IP (via
 * {@code pix_transactions.buyer_ip} ou {@code platform_activity_log.ip}) com um
 * resumo de vendas e o bot de vendas da loja (quando ativo).
 */
public record IpLookupResult(String ip, List<MerchantMatch> matches) {

    public record MerchantMatch(
            UUID merchantId,
            String name,
            String email,
            String status,
            long totalSales,
            Money averageTicket,
            Instant lastSaleAt,
            long sales7d,
            Money revenue7d,
            BotMatch bot) {

        /** Bot de vendas da loja (stores + plans + merchant_webhooks). */
        public record BotMatch(
                UUID storeId,
                String storeName,
                int activePlans,
                int activeWebhooks,
                String link) {
        }
    }
}
