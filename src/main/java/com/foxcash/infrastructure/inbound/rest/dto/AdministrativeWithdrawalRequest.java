package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request do saque administrativo do master (Spec 32) — recolhimento em nome do
 * lojista: {@code destinationKey} (chave de destino informada pelo master, sem
 * acessar os destinos privados do lojista) e {@code reason} (motivo, obrigatório
 * em ações que movem dinheiro).
 */
public record AdministrativeWithdrawalRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull String destinationKey,
        @NotNull String reason) {
}
