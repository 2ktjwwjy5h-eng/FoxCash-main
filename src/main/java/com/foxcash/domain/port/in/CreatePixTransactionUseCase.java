package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Porta de entrada: cria uma transacao Pix para um lojista.
 */
public interface CreatePixTransactionUseCase {

    Result create(Command command);

    /**
     * {@code idempotencyKey} e a chave de idempotência OBRIGATÓRIA do cliente
     * (Spec 28), única por lojista — o retry com a mesma chave nunca duplica.
     */
    record Command(UUID merchantId, Money amount, String description, String pixKey, String buyerId,
                   String idempotencyKey) {}

    record Result(UUID transactionId) {}
}
