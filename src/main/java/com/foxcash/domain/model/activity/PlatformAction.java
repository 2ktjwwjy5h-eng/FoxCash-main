package com.foxcash.domain.model.activity;

/**
 * Ações registradas na {@code platform_activity_log} (Spec 41) — o índice
 * cronológico unificado do que acontece em todas as contas (vendedor + master +
 * sistema).
 */
public enum PlatformAction {

    // Master
    MASTER_LOGIN,
    MASTER_LOGOUT,
    MASTER_CREATED,
    MASTER_ACTIVATED,
    MASTER_DEACTIVATED,
    // SEC-25 (revisão de segurança 2026-08-11): troca de senha do master —
    // evento registrado para rastreabilidade da rotação da credencial.
    MASTER_PASSWORD_CHANGED,

    // MFA do master (Spec 44) — sem MFA_UNLOCKED (lockout expira sozinho;
    // evento só entra junto com uma funcionalidade real de desbloqueio)
    MFA_ACTIVATED,
    MFA_DEACTIVATED,
    MFA_LOGIN_SUCCESS,
    MFA_LOGIN_FAILED,
    MFA_LOCKED,
    MFA_RECOVERY_USED,
    MFA_RECOVERY_REGENERATED,

    // Lojista / portal
    MERCHANT_REGISTERED,
    MERCHANT_LOGIN,
    MERCHANT_LOGOUT,
    PASSWORD_RESET,
    STORE_CREATED,
    STORE_UPDATED,
    BUTTON_CREATED,
    BUTTON_UPDATED,
    BUTTON_DELETED,
    MESSAGE_CREATED,
    MESSAGE_UPDATED,
    MESSAGE_DELETED,
    MESSAGE_SCHEDULED,
    PLAN_CREATED,
    PLAN_PURCHASED,
    DELIVERY_ACTION_COMPLETED,
    DELIVERY_COMPLETED,
    DELIVERY_ACCESS_RECORDED,

    // Bots (Spec 74) — registro central de bots de venda (Telegram/Discord).
    // Ações de escrita do ciclo de vida do bot; o token/secret nunca vão em
    // claro para a trilha (só máscara/identidade).
    BOT_REGISTERED,
    BOT_UPDATED,
    BOT_PAUSED,
    BOT_RESUMED,
    BOT_REMOVED,
    BOT_INTEGRATION_ERROR,
    // Broadcast (Spec 79) — transmissão em massa criada/agendada/cancelada pelo
    // lojista (actor MERCHANT). O conteúdo e a mídia nunca vão em claro para a
    // trilha (só modo/destino/status/timestamps).
    BROADCAST_CREATED,
    BROADCAST_SCHEDULED,
    BROADCAST_CANCELLED,
    // Tracking (Spec 81) — perfis de tracking (provedores de mídia) criados/
    // atualizados/ativados/desativados/duplicados/removidos pelo lojista (actor
    // MERCHANT). Tokens nunca vão em claro para a trilha (só máscara).
    TRACKING_PROFILE_CREATED,
    TRACKING_PROFILE_UPDATED,
    TRACKING_PROFILE_ACTIVATED,
    TRACKING_PROFILE_DEACTIVATED,
    TRACKING_PROFILE_DUPLICATED,
    TRACKING_PROFILE_DELETED,
    // CRM (Specs 83/90) — chat do painel e handoff humano por lead (actor
    // MERCHANT via portal; LEAD_BLOCKED também pode vir de SYSTEM via bot).
    // Mensagens e conteúdo nunca vão em claro na trilha (só metadados).
    CHAT_MESSAGE_SENT,
    LEAD_BLOCKED,
    LEAD_HANDOFF,

    // Operações financeiras
    CHARGE_CREATED,
    SALE_PAID,
    SALE_REFUNDED,
    CHARGEBACK_REGISTERED,
    WITHDRAWAL_REQUESTED,
    WITHDRAWAL_COMPLETED,
    WITHDRAWAL_FAILED,
    // Spec 120: Pagamento via Saldo (PVS) — Pix Copia e Cola estático pago com o
    // saldo do wallet (tipo BILL_PAYMENT no mesmo pipeline de saque). Trilha com
    // {amount} apenas — a chave do destino arbitrário nunca vai em claro.
    BILL_PAYMENT_REQUESTED,
    INVOICE_COLLECTED,
    // Spec 70: config de saque automático alterada pelo lojista (actor MERCHANT)
    // e saque automático criado pelo job (actor SYSTEM, sem IP).
    AUTO_WITHDRAW_SETTINGS_UPDATED,
    AUTO_WITHDRAW_REQUESTED,

    // Operacional / auditoria (Spec 67 — lookup de IP pelo operador)
    IP_LOOKUP,

    // Perfil de operação do portal (Spec 122 — seletor de contexto por perfil).
    // Trilha com {name}/{gatewayRoute} apenas (sem PII). O lojista cria/renomeia
    // perfis e fixa a rota de gateway (cobrança Pix) de cada um; o header
    // X-Profile-ID nunca decide rota (fonte da verdade é o banco).
    PROFILE_CREATED,
    PROFILE_UPDATED,
    GATEWAY_ROUTE_CHANGED,
    // Spec 139 — frota de bots do perfil substituída (PUT /profile/{id}/bots).
    // Trilha com {beforeCount}/{afterCount} (sem ids de bot na trilha).
    PROFILE_BOTS_CHANGED,

    // Spec 98: tentativa de cobrança em lojista honeypot (ambiente controlado) —
    // actor SYSTEM, trilha da Spec 41 com {merchantId, transactionId} apenas
    // (throttled por janela — o atacante não é alertado, o operador sim).
    HONEYPOT_TRIGGERED,

    // Spec 128: Risk Scoring Engine — ação automática (actor SYSTEM, trilha Spec
    // 41): reduz/restaura o rate limit do lojista por MED 30d alto (Gap 2).
    // details = {medPercent, reason, before, after} — sem PII.
    RISK_AUTO_RATE_LIMIT_REDUCED,
    RISK_AUTO_RATE_LIMIT_RESTORED
}
