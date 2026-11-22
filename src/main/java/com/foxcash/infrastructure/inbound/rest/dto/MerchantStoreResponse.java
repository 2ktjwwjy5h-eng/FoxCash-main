package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.Store;

import java.time.Instant;
import java.util.UUID;

/**
 * Store do lojista no painel do master (Spec 97, D3.4 — "Ver Shops") — espelho
 * read-only da loja do portal (Spec 48). **Sem {@code social_links}** (URLs
 * sensíveis do lojista) e sem {@code footer} (conteúdo de loja, não de
 * identidade). Logo/banner são públicos (Spec 48) — URLs opcionais mantidas.
 */
public record MerchantStoreResponse(
        UUID id,
        String name,
        String title,
        String color,
        String emoji,
        String description,
        String logoUrl,
        String bannerUrl,
        Instant createdAt,
        Instant updatedAt) {

    public static MerchantStoreResponse from(Store store) {
        return new MerchantStoreResponse(
                store.id(),
                store.name(),
                store.title(),
                store.color(),
                store.emoji(),
                store.description(),
                store.logoUrl(),
                store.bannerUrl(),
                store.createdAt(),
                store.updatedAt());
    }
}
