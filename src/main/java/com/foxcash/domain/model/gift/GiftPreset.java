package com.foxcash.domain.model.gift;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Valor predefinido do Gift PIX (Spec 23) — catálogo configurável por lojista
 * (defaults R$ 5/10/20/50 ativos) que o bot oferece ao cliente para compra
 * rápida sem digitar valor. O lojista ativa/desativa via portal.
 */
public class GiftPreset {

    private final UUID id;
    private final UUID merchantId;
    private final Money amount;
    private final String label;
    private boolean active;
    private final Instant createdAt;

    private GiftPreset(UUID id, UUID merchantId, Money amount, String label,
                       boolean active, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.label = label;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static GiftPreset create(UUID merchantId, Money amount, String label, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new GiftPreset(UUID.randomUUID(), merchantId, amount, label, true, now);
    }

    public static GiftPreset restore(UUID id, UUID merchantId, Money amount, String label,
                                     boolean active, Instant createdAt) {
        return new GiftPreset(id, merchantId, amount, label, active, createdAt);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public String label() {
        return label;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
