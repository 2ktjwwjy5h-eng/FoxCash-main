package com.foxcash.domain.model.fraud;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Disputa/chargeback registrado contra uma transacao (Spec 19).
 *
 * <p>{@code buyerIdHash} e o pseudonimo opaco (SHA-256) do comprador
 * denormalizado da transacao para consultas de velocidade/bloqueio sem join;
 * pode ser {@code null} se a transacao nao tinha buyerId.
 */
public class Chargeback {

    private final UUID id;
    private final UUID merchantId;
    private final UUID transactionId;
    private final String buyerIdHash;
    private final Money amount;
    private final String reason;
    private final Instant createdAt;

    public Chargeback(UUID id, UUID merchantId, UUID transactionId, String buyerIdHash,
                      Money amount, String reason, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.buyerIdHash = buyerIdHash;
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.reason = reason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static Chargeback register(UUID merchantId, UUID transactionId, String buyerIdHash,
                                      Money amount, String reason) {
        return new Chargeback(UUID.randomUUID(), merchantId, transactionId, buyerIdHash,
                amount, reason, Instant.now());
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public String buyerIdHash() {
        return buyerIdHash;
    }

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
