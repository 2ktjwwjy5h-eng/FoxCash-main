package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import jakarta.validation.constraints.Size;

/**
 * Request de conclusão de ação de entrega (Spec 49) — o bot confirma
 * {@code DONE}/{@code FAILED} com {@code note} opcional (motivo da falha).
 *
 * <p>SEC-27 (revisão de segurança 2026-08-11): {@code note} limitado a 255
 * caracteres — a coluna {@code plan_delivery_actions_exec.note} é
 * {@code VARCHAR(255)}; sem o teto, um note longo virava DataException (500).
 * Mesmo padrão do {@link DeliveryAccessRequest}.
 */
public record DeliveryCompleteRequest(
        DeliveryActionStatus status,
        @Size(max = 255) String note) {
}
