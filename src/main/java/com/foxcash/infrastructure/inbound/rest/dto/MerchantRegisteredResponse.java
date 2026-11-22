package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response do cadastro de lojista ({@code POST /api/v1/merchants}) — Spec 39.
 *
 * <p>Cadastro com nome, e-mail e senha: devolve a {@code apiKey} em texto claro
 * **uma unica vez** (Spec 12) — nunca persistida (apenas o hash SHA-256 fica no
 * banco). A senha escolhida NÃO é devolvida (o usuário a escolheu; apenas o hash
 * BCrypt é persistido). Nenhuma outra resposta expõe a chave.
 */
public record MerchantRegisteredResponse(
        UUID id,
        String name,
        String email,
        MerchantStatus status,
        BigDecimal transactionFee,
        BigDecimal monthlyFee,
        Instant createdAt,
        String apiKey) {

    public static MerchantRegisteredResponse from(Merchant merchant, String apiKey) {
        return new MerchantRegisteredResponse(
                merchant.id(),
                merchant.name(),
                merchant.email(),
                merchant.status(),
                merchant.pricingPlan().transactionFee().amount(),
                merchant.pricingPlan().monthlyFee().amount(),
                merchant.createdAt(),
                apiKey);
    }
}
