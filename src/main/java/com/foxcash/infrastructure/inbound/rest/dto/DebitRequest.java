package com.foxcash.infrastructure.inbound.rest.dto;

import java.math.BigDecimal;

/**
 * Débito administrativo manual do wallet (Spec 98 — D5): {@code amount > 0} com
 * 2 casas e {@code reason} obrigatório (400 {@code reason_required}, padrão
 * write-off Spec 34). Ação sensível com step-up TOTP (Spec 69).
 */
public record DebitRequest(BigDecimal amount, String reason) {
}
