package com.foxcash.domain.port.out;

import com.foxcash.domain.model.notification.OutboxNotification;

/**
 * Porta de saida: entrega de notificacoes ao lojista (e-mail, webhook, etc.).
 * Adapter atual: {@code LoggingMerchantNotifier} (mock, apenas loga).
 */
public interface MerchantNotifier {

    void deliver(OutboxNotification notification);
}
