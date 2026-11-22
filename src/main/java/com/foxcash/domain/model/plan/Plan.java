package com.foxcash.domain.model.plan;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plano da loja (Spec 49) — o card que o bot renderiza (nome, preço, imagem,
 * cor do embed, destaque) com as ações de entrega configuradas. Pertence à
 * loja ({@code storeId}); 1:N (vários planos por loja).
 */
public class Plan {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final UUID id;
    private final UUID storeId;
    private String name;
    private String description;
    private Money price;
    private String imageUrl;
    private String embedColor;
    private boolean featured;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    private Plan(UUID id, UUID storeId, String name, String description, Money price,
                 String imageUrl, String embedColor, boolean featured, boolean active,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.storeId = storeId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.embedColor = embedColor;
        this.featured = featured;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Plan create(UUID storeId, String name, Money price, String description,
                              String imageUrl, String embedColor, boolean featured, Instant now) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(name, price, description, imageUrl, embedColor);
        return new Plan(UUID.randomUUID(), storeId, name, description, price,
                imageUrl, embedColor, featured, true, now, now);
    }

    public static Plan restore(UUID id, UUID storeId, String name, String description, Money price,
                               String imageUrl, String embedColor, boolean featured, boolean active,
                               Instant createdAt, Instant updatedAt) {
        return new Plan(id, storeId, name, description, price,
                imageUrl, embedColor, featured, active, createdAt, updatedAt);
    }

    /** Atualiza os campos informados (não-nulos); bump em updatedAt. */
    public void update(String name, Money price, String description, String imageUrl,
                       String embedColor, Boolean featured, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        String newName = name != null ? name : this.name;
        Money newPrice = price != null ? price : this.price;
        String newDescription = description != null ? description : this.description;
        String newImageUrl = imageUrl != null ? imageUrl : this.imageUrl;
        String newEmbedColor = embedColor != null ? embedColor : this.embedColor;
        boolean newFeatured = featured != null ? featured : this.featured;
        validate(newName, newPrice, newDescription, newImageUrl, newEmbedColor);
        this.name = newName;
        this.price = newPrice;
        this.description = newDescription;
        this.imageUrl = newImageUrl;
        this.embedColor = newEmbedColor;
        this.featured = newFeatured;
        this.updatedAt = now;
    }

    public void activate() {
        this.active = true;
        touch();
    }

    public void deactivate() {
        this.active = false;
        touch();
    }

    private static void validate(String name, Money price, String description,
                                 String imageUrl, String embedColor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name e obrigatorio no plano");
        }
        if (name.length() > 120) {
            throw new IllegalArgumentException("name deve ter no maximo 120 caracteres");
        }
        if (price == null || price.amount().signum() <= 0) {
            throw new IllegalArgumentException("price deve ser positivo");
        }
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("description deve ter no maximo 500 caracteres");
        }
        if (imageUrl != null && imageUrl.length() > 500) {
            throw new IllegalArgumentException("imageUrl deve ter no maximo 500 caracteres");
        }
        if (embedColor != null && !HEX_COLOR.matcher(embedColor).matches()) {
            throw new IllegalArgumentException("embedColor deve estar no formato #RRGGBB");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Money price() {
        return price;
    }

    public String imageUrl() {
        return imageUrl;
    }

    public String embedColor() {
        return embedColor;
    }

    public boolean featured() {
        return featured;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
