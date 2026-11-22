package com.foxcash.domain.port.in;

import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consulta de entregas (Spec 49) — API do lojista/bot
 * (API key, {@code ROLE_MERCHANT}). Tudo por {@code merchantId} do principal
 * (404 cross-loja); o destinatário ({@code platformUserId}) e o payload das
 * ações são devolvidos **em claro apenas ao dono** (criptografados em repouso,
 * decifrados na leitura).
 */
public interface DeliveryQueryUseCase {

    /** Entregas PENDING da loja (polling do bot — Spec 52). */
    List<PlanDelivery> pending(UUID merchantId);

    /** Entrega da transação (404 {@code delivery_not_found} se não é da loja). */
    PlanDelivery byTransaction(UUID merchantId, UUID transactionId);

    /** Ações em execução da entrega (ordem de seq). */
    List<DeliveryActionExec> actions(UUID merchantId, UUID deliveryId);

    /** Logs de acesso ao conteúdo da entrega (provas, Spec 49+; ordem cronológica). */
    List<DeliveryAccessLog> accessLogs(UUID merchantId, UUID deliveryId);
}
