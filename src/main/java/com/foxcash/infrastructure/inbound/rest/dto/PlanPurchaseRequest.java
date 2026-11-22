package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.UUID;

/**
 * Request de compra de plano (Spec 49) — o bot informa a plataforma
 * ({@code DISCORD}|{@code TELEGRAM}) e o ID do comprador nela
 * ({@code platformUserId}, persistido criptografado). {@code buyerId} opcional
 * (antifraude), {@code idempotencyKey} obrigatória (Spec 28).
 *
 * <p>Spec 75: {@code botId} é opcional — quando presente, a cobrança é
 * atribuída ao bot de venda (funil {@code checkout_started}/{@code paid} e
 * métricas por bot). Sem {@code botId} → comportamento atual (zero regressão).
 *
 * <p>Provas de entrega (Spec 49+, 2026-08-06): {@code termsAccepted} registra o
 * aceite dos termos pelo comprador (o bot confirma; {@code true} grava o
 * timestamp). O IP e o User-Agent são capturados pelo servidor no header — o
 * bot não os envia.
 */
public record PlanPurchaseRequest(
        String platform,
        String platformUserId,
        String buyerId,
        Integer expirationSeconds,
        String idempotencyKey,
        Boolean termsAccepted,
        UUID botId) {
}
