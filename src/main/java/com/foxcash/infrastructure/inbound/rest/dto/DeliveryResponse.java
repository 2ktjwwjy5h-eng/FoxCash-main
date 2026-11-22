package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.Platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resposta de entrega (Spec 49) — o bot recebe o destinatário
 * ({@code platformUserId}) e as ações com payload **em claro apenas ao dono**
 * (criptografados em repouso). {@code payload} é decifrado pelo
 * {@code PlanActionPayloadCodec} antes da montagem.
 *
 * <p>Provas de entrega (Spec 49+, 2026-08-06): {@code termsAcceptedAt}/
 * {@code buyerIp}/{@code buyerDevice} (evidências capturadas na compra) e
 * {@code accessLogs} (histórico de acesso ao conteúdo) — evidência em disputa.
 */
public record DeliveryResponse(
        UUID id,
        UUID planId,
        UUID transactionId,
        Platform platform,
        String platformUserId,
        String buyerIdHash,
        PlanDeliveryStatus status,
        Instant createdAt,
        Instant deliveredAt,
        Instant termsAcceptedAt,
        String buyerIp,
        String buyerDevice,
        List<DeliveryActionExecResponse> actions,
        List<DeliveryAccessLogResponse> accessLogs) {

    public static DeliveryResponse of(PlanDelivery delivery, List<DeliveryActionExec> execs,
                                      Map<UUID, Map<String, Object>> payloads,
                                      List<DeliveryAccessLog> accessLogs) {
        return new DeliveryResponse(
                delivery.id(),
                delivery.planId(),
                delivery.transactionId(),
                delivery.platform(),
                delivery.platformUserId(),
                delivery.buyerIdHash(),
                delivery.status(),
                delivery.createdAt(),
                delivery.deliveredAt(),
                delivery.termsAcceptedAt(),
                delivery.buyerIp(),
                delivery.buyerDevice(),
                execs.stream().map(e -> DeliveryActionExecResponse.from(e, payloads.get(e.id()))).toList(),
                accessLogs.stream().map(DeliveryAccessLogResponse::from).toList());
    }

    public record DeliveryActionExecResponse(
            int seq,
            com.foxcash.domain.model.plan.DeliveryActionType actionType,
            Map<String, Object> payload,
            DeliveryActionStatus status,
            String note,
            Instant executedAt) {

        static DeliveryActionExecResponse from(DeliveryActionExec exec, Map<String, Object> payload) {
            return new DeliveryActionExecResponse(
                    exec.seq(), exec.actionType(), payload, exec.status(), exec.note(), exec.executedAt());
        }
    }

    public record DeliveryAccessLogResponse(
            Instant accessedAt,
            String ip,
            String device,
            String note) {

        static DeliveryAccessLogResponse from(DeliveryAccessLog log) {
            return new DeliveryAccessLogResponse(log.accessedAt(), log.ip(), log.device(), log.note());
        }
    }
}
