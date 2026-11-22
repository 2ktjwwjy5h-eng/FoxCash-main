package com.foxcash.domain.exception;

/**
 * Lojista autenticado tentou acessar recurso de outro lojista (posse violada) —
 * mapeada para 403 forbidden (Spec 18, ADR-009).
 */
public class MerchantAccessDeniedException extends RuntimeException {

    public MerchantAccessDeniedException() {
        super("Acesso negado a recurso de outro lojista");
    }
}
