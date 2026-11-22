package com.foxcash.domain.exception;

/**
 * Lojista existe, mas nao esta ativo para realizar operacoes.
 */
public class MerchantNotActiveException extends RuntimeException {

    public MerchantNotActiveException(java.util.UUID merchantId) {
        super("Merchant is not active: " + merchantId);
    }
}
