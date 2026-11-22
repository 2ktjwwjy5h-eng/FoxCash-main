package com.foxcash.domain.model.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Loja do vendedor (Spec 48) — a fonte de verdade que os bots renderizam.
 *
 * <p>1 loja por merchant (decisão de produto; o schema usa {@code merchant_id}
 * UNIQUE — modelagem preparada para 1:N no futuro sem redesenho). Dados de
 * identidade públicos (nome, logo, banner, descrição, redes sociais, cor,
 * emoji, título, rodapé) — o que for sensível (links privados de acesso) vive
 * no payload criptografado das ações de entrega (Spec 49).
 */
public class Store {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final int MAX_SOCIAL_LINKS = 10;

    private final UUID id;
    private final UUID merchantId;
    private String name;
    private String logoUrl;
    private String bannerUrl;
    private String description;
    private List<SocialLink> socialLinks;
    private String color;
    private String emoji;
    private String title;
    private String footer;
    private final Instant createdAt;
    private Instant updatedAt;

    /** Link de rede social da loja (label + url). */
    public record SocialLink(String label, String url) {

        public SocialLink {
            // Null/blank vira IllegalArgumentException (400 invalid_argument no
            // handler) — nunca NullPointerException (500) para payload invalido.
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(
                        "cada socialLink deve ter label entre 1 e 40 caracteres");
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException(
                        "cada socialLink deve ter uma url http(s) de ate 500 caracteres");
            }
        }
    }

    private Store(UUID id, UUID merchantId, String name, String logoUrl, String bannerUrl,
                  String description, List<SocialLink> socialLinks, String color, String emoji,
                  String title, String footer, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.logoUrl = logoUrl;
        this.bannerUrl = bannerUrl;
        this.description = description;
        this.socialLinks = socialLinks;
        this.color = color;
        this.emoji = emoji;
        this.title = title;
        this.footer = footer;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Cria a loja do vendedor (validações aplicadas). */
    public static Store create(UUID merchantId, String name, String logoUrl, String bannerUrl,
                               String description, List<SocialLink> socialLinks, String color,
                               String emoji, String title, String footer, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(name, logoUrl, bannerUrl, description, socialLinks, color, emoji, title, footer);
        Instant current = now;
        return new Store(UUID.randomUUID(), merchantId, name, logoUrl, bannerUrl, description,
                List.copyOf(socialLinks == null ? List.of() : socialLinks), color, emoji,
                title, footer, current, current);
    }

    public static Store restore(UUID id, UUID merchantId, String name, String logoUrl,
                                String bannerUrl, String description, List<SocialLink> socialLinks,
                                String color, String emoji, String title, String footer,
                                Instant createdAt, Instant updatedAt) {
        return new Store(id, merchantId, name, logoUrl, bannerUrl, description,
                socialLinks == null ? List.of() : List.copyOf(socialLinks), color, emoji,
                title, footer, createdAt, updatedAt);
    }

    /** Atualiza os campos informados (não-nulos) e valida o resultado; bump em updatedAt. */
    public void update(String name, String logoUrl, String bannerUrl, String description,
                       List<SocialLink> socialLinks, String color, String emoji, String title,
                       String footer, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        String newName = name != null ? name : this.name;
        String newLogoUrl = logoUrl != null ? logoUrl : this.logoUrl;
        String newBannerUrl = bannerUrl != null ? bannerUrl : this.bannerUrl;
        String newDescription = description != null ? description : this.description;
        List<SocialLink> newSocialLinks = socialLinks != null ? socialLinks : this.socialLinks;
        String newColor = color != null ? color : this.color;
        String newEmoji = emoji != null ? emoji : this.emoji;
        String newTitle = title != null ? title : this.title;
        String newFooter = footer != null ? footer : this.footer;
        validate(newName, newLogoUrl, newBannerUrl, newDescription, newSocialLinks, newColor,
                newEmoji, newTitle, newFooter);
        this.name = newName;
        this.logoUrl = newLogoUrl;
        this.bannerUrl = newBannerUrl;
        this.description = newDescription;
        this.socialLinks = List.copyOf(newSocialLinks);
        this.color = newColor;
        this.emoji = newEmoji;
        this.title = newTitle;
        this.footer = newFooter;
        this.updatedAt = now;
    }

    private static void validate(String name, String logoUrl, String bannerUrl, String description,
                                 List<SocialLink> socialLinks, String color, String emoji,
                                 String title, String footer) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name e obrigatorio na loja");
        }
        if (name.length() > 120) {
            throw new IllegalArgumentException("name deve ter no maximo 120 caracteres");
        }
        if (logoUrl != null && logoUrl.length() > 500) {
            throw new IllegalArgumentException("logoUrl deve ter no maximo 500 caracteres");
        }
        if (bannerUrl != null && bannerUrl.length() > 500) {
            throw new IllegalArgumentException("bannerUrl deve ter no maximo 500 caracteres");
        }
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("description deve ter no maximo 500 caracteres");
        }
        if (socialLinks != null) {
            if (socialLinks.size() > MAX_SOCIAL_LINKS) {
                throw new IllegalArgumentException(
                        "socialLinks deve ter no maximo " + MAX_SOCIAL_LINKS + " itens");
            }
            for (SocialLink link : socialLinks) {
                if (link.label() == null || link.label().isBlank() || link.label().length() > 40) {
                    throw new IllegalArgumentException(
                            "cada socialLink deve ter label entre 1 e 40 caracteres");
                }
                if (link.url() == null || link.url().isBlank() || link.url().length() > 500
                        || !link.url().startsWith("http")) {
                    throw new IllegalArgumentException(
                            "cada socialLink deve ter uma url http(s) de ate 500 caracteres");
                }
            }
        }
        if (color != null && !HEX_COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("color deve estar no formato #RRGGBB");
        }
        if (emoji != null && emoji.length() > 10) {
            throw new IllegalArgumentException("emoji deve ter no maximo 10 caracteres");
        }
        if (title != null && title.length() > 120) {
            throw new IllegalArgumentException("title deve ter no maximo 120 caracteres");
        }
        if (footer != null && footer.length() > 500) {
            throw new IllegalArgumentException("footer deve ter no maximo 500 caracteres");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String name() {
        return name;
    }

    public String logoUrl() {
        return logoUrl;
    }

    public String bannerUrl() {
        return bannerUrl;
    }

    public String description() {
        return description;
    }

    public List<SocialLink> socialLinks() {
        return new ArrayList<>(socialLinks);
    }

    public String color() {
        return color;
    }

    public String emoji() {
        return emoji;
    }

    public String title() {
        return title;
    }

    public String footer() {
        return footer;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
