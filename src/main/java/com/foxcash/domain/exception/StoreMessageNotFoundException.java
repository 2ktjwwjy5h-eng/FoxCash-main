package com.foxcash.domain.exception;

/**
 * Mensagem (etapa ou programada) da loja não encontrada para o lojista —
 * 404 {@code message_not_found}. Lançada por id solto fora da loja do
 * principal (cross-loja) sem revelar existência.
 */
public class StoreMessageNotFoundException extends RuntimeException {

    public StoreMessageNotFoundException() {
        super("mensagem nao encontrada para este lojista");
    }
}
