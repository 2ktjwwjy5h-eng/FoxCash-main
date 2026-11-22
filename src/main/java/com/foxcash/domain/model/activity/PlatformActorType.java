package com.foxcash.domain.model.activity;

/**
 * Tipo de ator de um evento da {@code platform_activity_log} (Spec 41) —
 * relatório unificado de atividades da plataforma.
 */
public enum PlatformActorType {
    /** Operador master (login/logout, gestão de perfis, ações administrativas). */
    MASTER,

    /** Lojista (cadastro, login, cobranças, saques...). */
    MERCHANT,

    /** Sistema (jobs agendados — sem IP; actor_id null). */
    SYSTEM
}
