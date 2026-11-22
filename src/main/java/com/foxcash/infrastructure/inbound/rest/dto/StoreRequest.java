package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.Store;

import java.util.List;

/**
 * Request de criacao/atualizacao da loja (Spec 48) — campos de identidade.
 *
 * <p>No {@code PATCH} os campos nulos significam "nao alterar"; no
 * {@code POST} o {@code name} e obrigatorio (o dominio valida). A validacao
 * final e do dominio ({@code Store.create}/{@code update}) — erros viram 400
 * {@code invalid_argument} (mesma convencao do restante da API).
 */
public record StoreRequest(
        String name,
        String logoUrl,
        String bannerUrl,
        String description,
        List<SocialLinkRequest> socialLinks,
        String color,
        String emoji,
        String title,
        String footer) {

    public record SocialLinkRequest(String label, String url) {
    }

    /** Converte os socialLinks do request em entidades de dominio. */
    public List<Store.SocialLink> toSocialLinks() {
        if (socialLinks == null) {
            return null;
        }
        return socialLinks.stream()
                .map(link -> new Store.SocialLink(link.label(), link.url()))
                .toList();
    }
}
