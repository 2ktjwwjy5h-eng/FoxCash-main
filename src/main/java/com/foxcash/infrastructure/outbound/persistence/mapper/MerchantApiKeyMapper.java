package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.ApiKeyStatus;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantApiKeyJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantApiKey} e a entidade JPA
 * {@link MerchantApiKeyJpaEntity} (Spec 24). O hash e o prefixo passam como
 * estao; os escopos sao serializados em CSV.
 */
public final class MerchantApiKeyMapper {

    private MerchantApiKeyMapper() {
    }

    public static MerchantApiKeyJpaEntity toEntity(MerchantApiKey key) {
        return new MerchantApiKeyJpaEntity(
                key.id(),
                key.merchantId(),
                key.label(),
                key.prefix(),
                key.keyHash(),
                key.status().name(),
                ApiKeyScope.toCsv(key.scopes()),
                key.lastUsedAt(),
                key.createdAt());
    }

    public static MerchantApiKey toDomain(MerchantApiKeyJpaEntity entity) {
        return MerchantApiKey.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getLabel(),
                entity.getPrefix(),
                entity.getKeyHash(),
                ApiKeyStatus.valueOf(entity.getStatus()),
                ApiKeyScope.fromCsv(entity.getScopes()),
                entity.getLastUsedAt(),
                entity.getCreatedAt());
    }
}
