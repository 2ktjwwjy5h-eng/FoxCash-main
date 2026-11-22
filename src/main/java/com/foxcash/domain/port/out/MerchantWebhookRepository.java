package com.foxcash.domain.port.out;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia dos webhooks de saida do lojista (Spec 35).
 * Privado por lojista — consultas sempre pelo merchantId do principal.
 */
public interface MerchantWebhookRepository {

    /** Webhooks ATIVOS do lojista inscritos no tipo de evento (para entrega). */
    List<MerchantWebhook> findAllActiveByMerchantAndEvent(UUID merchantId, WebhookEventType type);

    /** Webhook do lojista por id (404 cross-loja sem revelar existencia). */
    Optional<MerchantWebhook> findByMerchantIdAndId(UUID merchantId, UUID id);

    List<MerchantWebhook> findAllByMerchantId(UUID merchantId);

    MerchantWebhook save(MerchantWebhook webhook);
}
