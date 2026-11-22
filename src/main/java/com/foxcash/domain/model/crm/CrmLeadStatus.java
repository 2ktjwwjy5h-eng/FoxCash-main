package com.foxcash.domain.model.crm;

/**
 * Status de um lead no CRM (Spec 83) — DERIVADO automaticamente do funil
 * (Spec 75) na mesma transação dos eventos; nunca editado manualmente.
 * {@code BLOQUEADO} é flag: Telegram 403 "bot was blocked by the user" no envio
 * (ou falha de DM no Discord) → marca bloqueado (Spec 83 D6).
 */
public enum CrmLeadStatus {

    /** Só lead — nenhum evento de funil além do registro. */
    NOVO,

    /** {@code checkout_started} sem {@code paid} — checkout iniciado, não pago. */
    PENDENTE,

    /** {@code paid} — compra confirmada (Spec 75). */
    PAGO,

    /** Bot bloqueado pelo usuário — envios recusados (409 {@code lead_blocked}). */
    BLOQUEADO
}
