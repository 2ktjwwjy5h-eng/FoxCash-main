package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Caso de uso: realizar um saque automatico do wallet do lojista (Spec 14,
 * evoluido pela Spec 32).
 *
 * <p>O saque e automatico (sem aprovacao intermediaria): exige um destino
 * ATIVO do proprio lojista (Spec 31), valida ACTIVE + desbloqueio + saldo
 * suficiente, debita o saldo e registra a saida {@code COMPLETED} com o
 * destino na mesma transacao.
 */
public interface WithdrawalUseCase {

    Result withdraw(Command command);

    record Command(UUID merchantId, Money amount, UUID destinationId) {
    }

    record Result(UUID withdrawalId, Money amount, Money walletBalance) {
    }
}
