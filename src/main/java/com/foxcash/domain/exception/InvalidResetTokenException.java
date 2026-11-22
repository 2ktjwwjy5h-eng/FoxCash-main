package com.foxcash.domain.exception;

/**
 * Token de recuperação de senha inválido (desconhecido, expirado ou já usado) —
 * Spec 40.
 *
 * <p>Código único {@code invalid_token} para os três casos (sem oráculo sobre o
 * motivo): o token é um segredo de posse — quem não tem o token não deve
 * descobrir por que falhou.
 */
public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("Token de recuperacao de senha invalido, expirado ou ja utilizado");
    }
}
