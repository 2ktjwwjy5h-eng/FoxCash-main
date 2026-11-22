package com.foxcash.domain.model.master;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.merchant.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Dados cadastrais + wallet + phone + document do lojista para o painel do
 * master (Spec 97, D4 — {@code merchantWithFees}). Read-model agregado: o
 * {@code phone} (V60) e o {@code document} criptografado em repouso sao
 * expostos ao service do painel (que aplica a mascara antes do DTO); os
 * overrides da Spec 98 (taxa/limites/rate limit/controle) entram aqui via
 * {@code config} (V61) — o painel reflete o override automaticamente.
 */
public record MerchantPanelMerchant(
        UUID id,
        String name,
        String email,
        String document,
        MerchantStatus status,
        Money transactionFee,
        Money monthlyFee,
        Money walletBalance,
        String phone,
        Instant createdAt,
        MerchantConfig config) {
}
