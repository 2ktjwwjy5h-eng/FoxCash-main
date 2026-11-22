package com.foxcash.domain.port.in;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consulta de broadcasts no portal (Spec 79),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal —
 * nunca por id solto (404 {@code broadcast_not_found} cross-lojista).
 */
public interface BroadcastQueryUseCase {

    /** Lista do lojista (filtro opcional por modo/status). */
    List<Broadcast> list(UUID merchantId, BroadcastMode mode, BroadcastStatus status);

    /**
     * Lista do lojista escopada por bot-set (Spec 124 — filtro rígido por
     * perfil): {@code botIds} null → todos os broadcasts do lojista
     * (regressão — sem header); presente → só os de bots do perfil (vazio →
     * sem resultados — header inválido nunca vaza).
     */
    List<Broadcast> list(UUID merchantId, List<UUID> botIds,
                         BroadcastMode mode, BroadcastStatus status);

    /** Detalhe do broadcast do lojista (404 cross-lojista). */
    Broadcast get(UUID merchantId, UUID broadcastId);

    /** Logs por destinatário do broadcast (portal — detalhe + logs). */
    List<BroadcastLog> logs(UUID merchantId, UUID broadcastId);
}
