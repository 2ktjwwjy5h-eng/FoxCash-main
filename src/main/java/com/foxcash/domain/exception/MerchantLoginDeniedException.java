package com.foxcash.domain.exception;

/**
 * Login do portal negado porque o lojista nao esta {@code ACTIVE}
 * (SUSPENDED/INACTIVE) — Spec 20: 403 {@code merchant_not_active}.
 *
 * <p>Distinta de {@link MerchantNotActiveException} (409) porque o login e um
 * pedido de acesso, nao uma operacao sobre um recurso: o status correto e
 * Forbidden, nao Conflict.
 */
public class MerchantLoginDeniedException extends RuntimeException {

    public MerchantLoginDeniedException(java.util.UUID merchantId) {
        super("Merchant is not active: " + merchantId);
    }
}
