package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Criação de destino de saque (Spec 31). Para chave Pix: {@code value}; para
 * dados bancários: {@code bankCode}/{@code agency}/{@code account} (+
 * {@code accountType} opcional e {@code value} = chave Pix associada opcional).
 *
 * <p>Auditoria 2026-08-05 (M2): limites de tamanho alinhados ao banco — os
 * campos sensiveis sao criptografados (ciphertext > texto claro), entao limites
 * conservadores evitam 500 por estouro da coluna VARCHAR(255)/VARCHAR(60).
 */
public record WithdrawalDestinationRequest(
        @NotNull WithdrawalDestinationType type,
        @Size(max = 100) String value,
        @Size(max = 10) String bankCode,
        @Size(max = 10) String agency,
        @Size(max = 30) String account,
        @Size(max = 20) String accountType,
        @Size(max = 60) String label) {
}
