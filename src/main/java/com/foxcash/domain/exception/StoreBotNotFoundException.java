package com.foxcash.domain.exception;

/**
 * Bot de venda inexistente (ou fora do escopo do lojista) — 404
 * {@code bot_not_found}. Sempre por {@code merchantId} do principal (padrão
 * IDOR das Specs 24/31): cross-lojista → 404, nunca revela existência.
 */
public class StoreBotNotFoundException extends RuntimeException {

    public StoreBotNotFoundException() {
        super("Bot de venda nao encontrado");
    }
}
