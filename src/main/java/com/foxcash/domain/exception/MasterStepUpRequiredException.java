package com.foxcash.domain.exception;

/**
 * Acao sensivel do master sem step-up MFA valido (Spec 69) → 403
 * {@code step_up_required} — a sessao e valida, falta a re-prova de posse
 * dentro da janela (default 15 min). Distinto de {@code forbidden} (sem
 * permissao) e de {@code unauthorized} (sessao morta — 401 derrubaria o
 * cliente).
 */
public class MasterStepUpRequiredException extends RuntimeException {

    public MasterStepUpRequiredException() {
        super("Re-autenticacao (step-up) exigida para esta acao sensivel");
    }
}
