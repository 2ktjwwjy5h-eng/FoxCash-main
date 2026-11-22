package com.foxcash.domain.exception;

/**
 * Lojista nao encontrado.
 */
public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(java.util.UUID merchantId) {
        super("Merchant not found: " + merchantId);
    }
}
