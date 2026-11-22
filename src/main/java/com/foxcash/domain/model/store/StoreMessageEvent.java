package com.foxcash.domain.model.store;

/**
 * Etapa da venda de uma mensagem automática da loja (Spec 51) — o texto que o
 * vendedor edita no painel e o bot renderiza no fluxo (Spec 52):
 * {@code WELCOME} (boas-vindas ao abrir o menu), {@code AWAITING_PAYMENT}
 * (junto ao QR), {@code PAYMENT_RECEIVED} (SALE_PAID, antes da entrega),
 * {@code DELIVERED} (após as ações de entrega, Spec 49), {@code PROMO} e
 * {@code FAQ} (botões da Spec 50). 1 mensagem por event por loja.
 */
public enum StoreMessageEvent {

    WELCOME,
    AWAITING_PAYMENT,
    PAYMENT_RECEIVED,
    DELIVERED,
    PROMO,
    FAQ;

    /** Parse de request: aceita maiúsculas/minúsculas; inválido → 400. */
    public static StoreMessageEvent parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "event e obrigatorio (WELCOME|AWAITING_PAYMENT|PAYMENT_RECEIVED|DELIVERED|PROMO|FAQ)");
        }
        try {
            return StoreMessageEvent.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("event invalido na mensagem da loja: " + raw);
        }
    }
}
