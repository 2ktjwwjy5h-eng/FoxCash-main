package com.foxcash.domain.port.in;

import java.util.UUID;

/**
 * Porta de entrada: registra um chargeback contra uma transacao (Spec 19).
 * Fonte dos dados: lojista/bot (disputa reportada pelo comprador) ou master
 * (notificacao do PSP).
 */
public interface RegisterChargebackUseCase {

    Result register(UUID merchantId, UUID transactionId, String reason);

    record Result(UUID chargebackId) {
    }
}
