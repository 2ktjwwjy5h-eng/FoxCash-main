package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.MerchantApiKey;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Resposta de listagem de chaves de API do portal (Spec 24) — expoe apenas o
 * prefixo (identificacao), status, escopos e ultimo uso. O segredo/hash nunca
 * e exposto.
 */
public record ApiKeyResponse(
        UUID id,
        String label,
        String prefix,
        String status,
        Set<ApiKeyScope> scopes,
        Instant lastUsedAt,
        Instant createdAt) {

    public static ApiKeyResponse from(MerchantApiKey key) {
        return new ApiKeyResponse(
                key.id(),
                key.label(),
                key.prefix(),
                key.status().name(),
                key.scopes(),
                key.lastUsedAt(),
                key.createdAt());
    }

    public static List<ApiKeyResponse> from(List<MerchantApiKey> keys) {
        return keys.stream().map(ApiKeyResponse::from).toList();
    }
}
