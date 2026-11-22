package com.foxcash.domain.port.in;

import com.foxcash.domain.model.plan.Platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: compra de plano (Spec 49) — {@code POST
 * /api/v1/foxcash/plans/{id}/purchase} (API key do lojista). Cria a cobrança Pix
 * com {@code planId} (idempotência Spec 28 + triagem antifraude Spec 19
 * intactas) e persiste o destinatário ({@code platform} + {@code platformUserId})
 * na transação para a entrega automática.
 *
 * <p>Provas de entrega (Spec 49+, 2026-08-06): {@code termsAcceptedAt}
 * (aceite dos termos), {@code buyerIp} e {@code buyerDevice} são capturados no
 * request (HTTP) e fluem até a entrega — evidência em caso de disputa.
 */
public interface PlanPurchaseUseCase {

    Result purchase(UUID merchantId, UUID planId, PurchaseCommand command);

    record PurchaseCommand(Integer expirationSeconds, String buyerId, String idempotencyKey,
                           Platform platform, String platformUserId,
                           Instant termsAcceptedAt, String buyerIp, String buyerDevice,
                           UUID botId) {

        /** Compatibilidade: compra sem provas de entrega (evidências null). */
        public PurchaseCommand(Integer expirationSeconds, String buyerId, String idempotencyKey,
                               Platform platform, String platformUserId) {
            this(expirationSeconds, buyerId, idempotencyKey, platform, platformUserId,
                    null, null, null, null);
        }
    }

    record Result(UUID transactionId) {
    }
}
