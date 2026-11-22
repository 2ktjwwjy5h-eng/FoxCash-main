package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da config de saque automático por limiar de saldo (Spec 70) —
 * desacoplada do domínio. 1:1 por lojista ({@code merchant_id UNIQUE}); linha
 * ausente = desabilitado. {@code destination_id NOT NULL} (o destino só é
 * removido com o automático desativado — ON DELETE RESTRICT).
 */
@Entity
@Table(name = "merchant_auto_withdraw_config")
public class AutoWithdrawConfigJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "threshold_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal thresholdAmount;

    @Column(name = "destination_id", nullable = false)
    private UUID destinationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AutoWithdrawConfigJpaEntity() {
    }

    public AutoWithdrawConfigJpaEntity(UUID id, UUID merchantId, boolean enabled,
                                       BigDecimal thresholdAmount, UUID destinationId,
                                       Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.enabled = enabled;
        this.thresholdAmount = thresholdAmount;
        this.destinationId = destinationId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
