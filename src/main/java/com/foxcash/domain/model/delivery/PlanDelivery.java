package com.foxcash.domain.model.delivery;

import com.foxcash.domain.model.plan.Platform;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Entrega do plano (Spec 49) — criada quando a transação do plano é aprovada.
 * Guarda o destinatário (platform + platform_user_id, criptografados em
 * repouso) — o bot é stateless e recebe o destinatário da API.
 *
 * <p>Transição automática em {@link #evaluate}: todas as ações DONE →
 * DELIVERED; qualquer FAILED → FAILED; plano sem ações → {@link #markDelivered}.
 * Retry ({@link #reopenForRetry}) volta FAILED → PENDING (o bot reprocessa).
 */
public class PlanDelivery {

    private final UUID id;
    private final UUID storeId;
    private final UUID planId;
    private final UUID transactionId;
    private final Platform platform;
    private final String platformUserId;
    private final String buyerIdHash;
    // Provas de entrega (Spec 49+, 2026-08-06): copiadas da transação na criação.
    private final Instant termsAcceptedAt;
    private final String buyerIp;
    private final String buyerDevice;
    private PlanDeliveryStatus status;
    private final Instant createdAt;
    private Instant deliveredAt;

    private PlanDelivery(UUID id, UUID storeId, UUID planId, UUID transactionId,
                         Platform platform, String platformUserId, String buyerIdHash,
                         PlanDeliveryStatus status, Instant createdAt, Instant deliveredAt,
                         Instant termsAcceptedAt, String buyerIp, String buyerDevice) {
        this.id = id;
        this.storeId = storeId;
        this.planId = planId;
        this.transactionId = transactionId;
        this.platform = platform;
        this.platformUserId = platformUserId;
        this.buyerIdHash = buyerIdHash;
        this.termsAcceptedAt = termsAcceptedAt;
        this.buyerIp = buyerIp;
        this.buyerDevice = buyerDevice;
        this.status = status;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
    }

    public static PlanDelivery create(UUID storeId, UUID planId, UUID transactionId,
                                      Platform platform, String platformUserId, String buyerIdHash,
                                      Instant now) {
        return create(storeId, planId, transactionId, platform, platformUserId, buyerIdHash,
                now, null, null, null);
    }

    /**
     * Cria a entrega com as provas (Spec 49+): aceite dos termos + IP/dispositivo
     * do comprador, copiados da transação aprovada — evidência em disputas.
     */
    public static PlanDelivery create(UUID storeId, UUID planId, UUID transactionId,
                                      Platform platform, String platformUserId, String buyerIdHash,
                                      Instant now, Instant termsAcceptedAt, String buyerIp,
                                      String buyerDevice) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        if (platform == null) {
            // IAE (400 invalid_argument no handler) — nunca NPE (500).
            throw new IllegalArgumentException("platform e obrigatoria na entrega");
        }
        if (platformUserId == null || platformUserId.isBlank()) {
            throw new IllegalArgumentException("platformUserId e obrigatorio na entrega");
        }
        return new PlanDelivery(UUID.randomUUID(), storeId, planId, transactionId,
                platform, platformUserId, buyerIdHash, PlanDeliveryStatus.PENDING, now, null,
                termsAcceptedAt, buyerIp, buyerDevice);
    }

    public static PlanDelivery restore(UUID id, UUID storeId, UUID planId, UUID transactionId,
                                       Platform platform, String platformUserId, String buyerIdHash,
                                       PlanDeliveryStatus status, Instant createdAt, Instant deliveredAt) {
        return restore(id, storeId, planId, transactionId, platform, platformUserId, buyerIdHash,
                status, createdAt, deliveredAt, null, null, null);
    }

    public static PlanDelivery restore(UUID id, UUID storeId, UUID planId, UUID transactionId,
                                       Platform platform, String platformUserId, String buyerIdHash,
                                       PlanDeliveryStatus status, Instant createdAt, Instant deliveredAt,
                                       Instant termsAcceptedAt, String buyerIp, String buyerDevice) {
        return new PlanDelivery(id, storeId, planId, transactionId, platform, platformUserId,
                buyerIdHash, status, createdAt, deliveredAt, termsAcceptedAt, buyerIp, buyerDevice);
    }

    /** Plano sem ações → entrega nasce DELIVERED (no-op). Gate: só PENDING. */
    public boolean markDelivered(Instant now) {
        if (status != PlanDeliveryStatus.PENDING) {
            return false;
        }
        this.status = PlanDeliveryStatus.DELIVERED;
        this.deliveredAt = now;
        return true;
    }

    /**
     * Avalia a entrega após a conclusão de uma ação (mesma transação): qualquer
     * ação FAILED → entrega FAILED; todas DONE → DELIVERED; senão permanece
     * PENDING. Gate: só PENDING transiciona.
     */
    public boolean evaluate(List<DeliveryActionExec> actions, Instant now) {
        if (status != PlanDeliveryStatus.PENDING) {
            return false;
        }
        boolean anyFailed = actions.stream()
                .anyMatch(a -> a.status() == DeliveryActionStatus.FAILED);
        if (anyFailed) {
            this.status = PlanDeliveryStatus.FAILED;
            return true;
        }
        boolean allDone = !actions.isEmpty() && actions.stream()
                .allMatch(a -> a.status() == DeliveryActionStatus.DONE);
        if (allDone) {
            this.status = PlanDeliveryStatus.DELIVERED;
            this.deliveredAt = now;
            return true;
        }
        return false;
    }

    /** Retry: entrega FAILED → PENDING (as ações FAILED são reabertas pelo serviço). */
    public boolean reopenForRetry() {
        if (status != PlanDeliveryStatus.FAILED) {
            return false;
        }
        this.status = PlanDeliveryStatus.PENDING;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID planId() {
        return planId;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public Platform platform() {
        return platform;
    }

    public String platformUserId() {
        return platformUserId;
    }

    public String buyerIdHash() {
        return buyerIdHash;
    }

    public PlanDeliveryStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }

    /** Aceite dos termos do comprador (prova de entrega, Spec 49+); null se não aceitou. */
    public Instant termsAcceptedAt() {
        return termsAcceptedAt;
    }

    /** IP de origem do comprador na compra (prova de entrega, Spec 49+); null se não capturado. */
    public String buyerIp() {
        return buyerIp;
    }

    /** User-Agent do dispositivo do comprador na compra (prova, Spec 49+); null se não capturado. */
    public String buyerDevice() {
        return buyerDevice;
    }
}
