package com.foxcash.domain.model.admin;

import com.foxcash.domain.model.merchant.MerchantStatus;

import java.time.Instant;

/**
 * Filtros/paginação da consulta administrativa de lojistas (dashboard).
 *
 * @param status      filtro por status (opcional)
 * @param query       texto livre sobre {@code name} (opcional, case-insensitive)
 * @param createdFrom início da janela de criação ({@code createdAt}, opcional)
 * @param createdTo   fim da janela de criação ({@code createdAt}, opcional)
 * @param page        número da página (0-based)
 * @param size        tamanho da página
 */
public record MerchantSearchQuery(
        MerchantStatus status,
        String query,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size) {
}
