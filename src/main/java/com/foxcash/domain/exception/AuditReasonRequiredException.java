package com.foxcash.domain.exception;

/**
 * Acao administrativa que altera dinheiro sem motivo (Spec 37): o master deve
 * informar {@code reason} em saque administrativo (32), write-off (34) e demais
 * acoes financeiras. Mapeado para 400 {@code reason_required}.
 */
public class AuditReasonRequiredException extends RuntimeException {

    public AuditReasonRequiredException() {
        super("Reason é obrigatório em ações administrativas que alteram dinheiro");
    }
}
