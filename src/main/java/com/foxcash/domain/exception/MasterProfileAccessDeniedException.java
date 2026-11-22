package com.foxcash.domain.exception;

/**
 * Perfil master sem a flag {@code canManageMasters} tentou acessar uma rota de
 * gestão de perfis/relatório (Spec 41) → 403 {@code forbidden}.
 */
public class MasterProfileAccessDeniedException extends RuntimeException {

    public MasterProfileAccessDeniedException() {
        super("Apenas o admin (can_manage_masters) acessa a gestao de perfis master");
    }
}
