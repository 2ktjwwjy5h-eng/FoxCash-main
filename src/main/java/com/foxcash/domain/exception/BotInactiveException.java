package com.foxcash.domain.exception;

/**
 * Bot de venda não está {@code ACTIVE} na compra com {@code botId} (Spec 75) —
 * 409 {@code bot_inactive}. Um bot PAUSED/ERROR/REGISTERING não pode gerar
 * cobranças atribuídas a ele.
 */
public class BotInactiveException extends RuntimeException {

    public BotInactiveException() {
        super("Bot de venda nao esta ativo para a compra");
    }
}
