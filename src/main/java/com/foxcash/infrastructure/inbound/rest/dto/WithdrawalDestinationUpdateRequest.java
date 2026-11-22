package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Size;

/**
 * Atualização de destino de saque (Spec 31) — apenas o rótulo é editável.
 * Auditoria 2026-08-05 (M2): limite alinhado à coluna VARCHAR(60).
 */
public record WithdrawalDestinationUpdateRequest(@Size(max = 60) String label) {
}
