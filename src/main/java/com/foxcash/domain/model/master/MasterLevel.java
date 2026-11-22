package com.foxcash.domain.model.master;

/**
 * Nivel do perfil master (Spec 69, decisao do usuario 2026-08-11) — autorizacao
 * em 2 niveis, derivada da flag {@code can_manage_masters} (sem campo novo):
 *
 * <ul>
 *   <li>{@link #OPERATOR} (default, {@code can_manage_masters=false}) — bloco
 *       operacional completo (OPERATOR+FINANCE+AUDITOR juntos): as 7 permissoes
 *       de acao;</li>
 *   <li>{@link #ADMIN} ({@code can_manage_masters=true}) — bloco completo +
 *       {@code master.profiles.manage}.</li>
 * </ul>
 */
public enum MasterLevel {

    OPERATOR,
    ADMIN;

    /** Deriva o nivel do master a partir da flag {@code can_manage_masters}. */
    public static MasterLevel of(Master master) {
        return master.canManageMasters() ? ADMIN : OPERATOR;
    }
}
