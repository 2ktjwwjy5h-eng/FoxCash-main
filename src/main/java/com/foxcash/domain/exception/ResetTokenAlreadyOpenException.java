package com.foxcash.domain.exception;

/**
 * Corrida no {@code forgot-password}: o lojista já possui um token de
 * recuperação ativo e o índice único parcial
 * {@code uq_password_reset_open_per_merchant} rejeitou o novo (Spec 40).
 *
 * <p>Raro (o serviço apaga o token aberto antes de inserir) — o índice é o
 * backstop; o cliente pode simplesmente repetir o pedido.
 */
public class ResetTokenAlreadyOpenException extends RuntimeException {

    public ResetTokenAlreadyOpenException() {
        super("Ja existe um token de recuperacao de senha ativo para este lojista");
    }
}
