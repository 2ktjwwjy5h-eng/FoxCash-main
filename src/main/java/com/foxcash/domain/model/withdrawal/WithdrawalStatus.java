package com.foxcash.domain.model.withdrawal;

/**
 * Status de uma saída (saque) do lojista.
 *
 * <p>A partir da Spec 36 o saque nasce {@code PROCESSING} (o débito é imediato
 * na solicitação; o PSP processa em seguida) e transita para {@code COMPLETED}
 * (confirmado) ou {@code FAILED} (estorno automático do débito ao saldo) via
 * webhook de saída ou reconciliação. {@code REJECTED} reservado para fluxos
 * futuros.
 */
public enum WithdrawalStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    REJECTED
}
