package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Caso de uso: saque administrativo do master em nome do lojista (Spec 32) —
 * bloqueio "lacrado".
 *
 * <p>Quando o master bloqueia os saques, o bloqueio é total para o vendedor,
 * mas o master pode recolher valores (taxas, chargebacks, encerramento)
 * <b>apenas até o saldo disponível</b> (resultado nunca negativo). O master
 * informa a chave de destino no request (não acessa os destinos privados do
 * lojista — privacidade da Spec 31). O recolhimento registra {@code
 * ADMIN_RECOVERY} no ledger do master (Spec 34) e a trilha de auditoria
 * {@code ADMINISTRATIVE_WITHDRAWAL} (Spec 37, reason obrigatório) — tudo na
 * mesma transação.
 */
public interface MasterWithdrawalUseCase {

    Result administrativeWithdraw(UUID merchantId, Money amount, String destinationKey,
                                  String reason, UUID actorId, String ip);

    record Result(UUID withdrawalId, Money amount, Money walletBalance) {
    }
}
