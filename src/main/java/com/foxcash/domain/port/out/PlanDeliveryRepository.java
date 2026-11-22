package com.foxcash.domain.port.out;

import com.foxcash.domain.model.delivery.PlanDelivery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência das entregas de plano (Spec 49). Por
 * {@code storeId} (implica o merchant) — o bot/API key do lojista dono acessa
 * apenas as próprias entregas (404 cross-loja).
 */
public interface PlanDeliveryRepository {

    PlanDelivery save(PlanDelivery delivery);

    /** Busca por id E store (404 cross-loja). */
    Optional<PlanDelivery> findByIdAndStoreId(UUID deliveryId, UUID storeId);

    /** Entregas PENDING da loja (polling do bot — Spec 52). */
    List<PlanDelivery> findByStoreIdAndStatusPending(UUID storeId);

    /** Entrega de uma transação (unique — replay de PAID não duplica). */
    Optional<PlanDelivery> findByTransactionId(UUID transactionId);

    /**
     * Compradores da loja com entrega entregue (platform_user_id, deduplicados)
     * — destinatários das mensagens programadas (Spec 51, audience BUYERS).
     */
    List<String> findDistinctDeliveredPlatformUserIds(UUID storeId);
}
