package com.foxcash.domain.model.audit;

/**
 * Tipo de acao administrativa auditada (Spec 37) — cada valor corresponde a um
 * ponto de acao do master que gera uma entrada em {@code master_audit_log}.
 */
public enum MasterAuditAction {

    /** Bloqueio de saques do lojista (Spec 14). */
    WITHDRAWALS_BLOCK,

    /** Liberacao de saques do lojista (Spec 14). */
    WITHDRAWALS_RELEASE,

    /** Saque administrativo do master em nome do lojista (Spec 32). */
    ADMINISTRATIVE_WITHDRAWAL,

    /** Baixa (write-off) de receivable (Spec 34). */
    RECEIVABLE_WRITE_OFF,

    /** Cobranca manual de fatura (Spec 33). */
    INVOICE_COLLECT,

    /** Ajuste de risco do lojista (Spec 19 — reservado). */
    RISK_ADJUSTMENT,

    /** Criacao de perfil master (Spec 41). */
    MASTER_CREATED,

    /** Ativacao de perfil master (Spec 41). */
    MASTER_ACTIVATED,

    /** Desativacao de perfil master (Spec 41). */
    MASTER_DEACTIVATED,

    /** Re-prova de posse (step-up MFA) para acao sensivel (Spec 69 — D4.4). */
    MASTER_STEP_UP,

    /** Cancelamento forcado de broadcast pelo master (Spec 89 — PENDING → FAILED). */
    BROADCAST_FORCE_CANCELLED,

    /** Atualizacao da config fina do lojista (Spec 98 — PUT /config). */
    MERCHANT_CONFIG_UPDATED,

    /** Debito administrativo manual do wallet (Spec 98 — POST /config/debit). */
    ADMIN_DEBIT,

    /** Lojista marcado como honeypot (Spec 98 — ambiente controlado de monitoramento antifraude). */
    HONEYPOT_MARKED,

    /** Lojista desmarcado como honeypot (Spec 98 — reversivel). */
    HONEYPOT_UNMARKED,

    /** Lojista banido (Spec 98 — BANNED irreversivel). */
    MERCHANT_BANNED,

    /** Kill switch: lojista ativado (Spec 98 — POST /config/activate). */
    MERCHANT_ACTIVATED,

    /** Kill switch: lojista desativado (Spec 98 — POST /config/deactivate). */
    MERCHANT_DEACTIVATED,

    /** Post da Comunidade criado (Spec 119 — Master Console). */
    COMMUNITY_POST_CREATED,

    /** Post da Comunidade editado (Spec 119 — Master Console). */
    COMMUNITY_POST_UPDATED,

    /** Post da Comunidade removido (Spec 119 — Master Console). */
    COMMUNITY_POST_DELETED;
}
