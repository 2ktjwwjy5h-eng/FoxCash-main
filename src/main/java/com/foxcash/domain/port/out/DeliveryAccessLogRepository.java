package com.foxcash.domain.port.out;

import com.foxcash.domain.model.delivery.DeliveryAccessLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída: persistência dos logs de acesso ao conteúdo entregue
 * (Spec 49+, 2026-08-06). Por {@code deliveryId} — a entrega já é escopada
 * pela loja (404 cross-loja na camada de aplicação).
 */
public interface DeliveryAccessLogRepository {

    DeliveryAccessLog save(DeliveryAccessLog log);

    /** Logs de acesso de uma entrega, em ordem cronológica. */
    List<DeliveryAccessLog> findByDeliveryIdOrderByAccessedAt(UUID deliveryId);
}
