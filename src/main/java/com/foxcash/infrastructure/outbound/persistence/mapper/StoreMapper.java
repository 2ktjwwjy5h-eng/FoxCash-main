package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.store.Store;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreJpaEntity;

import java.util.List;

/**
 * Mapeia entre o modelo de dominio {@link Store} e a entidade JPA
 * {@link StoreJpaEntity} (Spec 48).
 *
 * <p>{@code social_links} e persistido como string JSON (array de
 * {@code {label, url}}; coluna {@code social_links TEXT}) — a serializacao
 * acontece aqui, na camada de infraestrutura. JSON invalido no banco (nunca
 * esperado; coluna gravada apenas por este mapper) vira excecao de
 * infraestrutura (500).
 */
public final class StoreMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Store.SocialLink>> SOCIAL_LINKS_TYPE =
            new TypeReference<>() {
            };

    private StoreMapper() {
    }

    public static StoreJpaEntity toEntity(Store store) {
        return new StoreJpaEntity(
                store.id(),
                store.merchantId(),
                store.name(),
                store.logoUrl(),
                store.bannerUrl(),
                store.description(),
                serializeSocialLinks(store.socialLinks()),
                store.color(),
                store.emoji(),
                store.title(),
                store.footer(),
                store.createdAt(),
                store.updatedAt());
    }

    public static Store toDomain(StoreJpaEntity entity) {
        return Store.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getName(),
                entity.getLogoUrl(),
                entity.getBannerUrl(),
                entity.getDescription(),
                deserializeSocialLinks(entity.getSocialLinks()),
                entity.getColor(),
                entity.getEmoji(),
                entity.getTitle(),
                entity.getFooter(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String serializeSocialLinks(List<Store.SocialLink> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(links);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar social_links da loja", e);
        }
    }

    private static List<Store.SocialLink> deserializeSocialLinks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, SOCIAL_LINKS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao desserializar social_links da loja", e);
        }
    }
}
