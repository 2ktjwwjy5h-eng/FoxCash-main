package com.foxcash.domain.exception;

/**
 * Master sem a permissao da acao tentou executa-la (Spec 69) → 403
 * {@code forbidden} (mesmo code do {@code MasterProfileAccessDeniedException} —
 * nenhuma distincao ao atacante).
 */
public class MasterPermissionDeniedException extends RuntimeException {

    public MasterPermissionDeniedException() {
        super("Master sem permissao para executar a acao");
    }
}
