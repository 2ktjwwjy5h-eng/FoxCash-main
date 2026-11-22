package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.Store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta da loja do vendedor (Spec 48) — identidade completa da loja do
 * principal (portal e storefront). Dados públicos da loja (sem PII).
 */
public record StoreResponse(
        UUID id,
        String name,
        String logoUrl,
        String bannerUrl,
        String description,
        List<SocialLinkResponse> socialLinks,
        String color,
        String emoji,
        String title,
        String footer,
        Instant createdAt,
        Instant updatedAt) {

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.id(),
                store.name(),
                store.logoUrl(),
                store.bannerUrl(),
                store.description(),
                store.socialLinks().stream().map(SocialLinkResponse::from).toList(),
                store.color(),
                store.emoji(),
                store.title(),
                store.footer(),
                store.createdAt(),
                store.updatedAt());
    }

    public record SocialLinkResponse(String label, String url) {

        public static SocialLinkResponse from(Store.SocialLink link) {
            return new SocialLinkResponse(link.label(), link.url());
        }
    }
}
