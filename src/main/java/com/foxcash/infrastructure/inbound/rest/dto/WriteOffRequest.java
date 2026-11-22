package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request da baixa (write-off) de receivable (Spec 34) — {@code reason} e
 * obrigatorio (acao que altera dinheiro, auditada pela Spec 37).
 *
 * <p>A validacao de {@code reason} (null/blank) e feita no dominio
 * ({@code ReceivableWriteOffService} → {@code AuditReasonRequiredException},
 * 400 {@code reason_required}) — nao duplicar com Bean Validation, senao o
 * code de erro vira {@code validation_error} antes de chegar ao service.
 */
public record WriteOffRequest(String reason) {
}
