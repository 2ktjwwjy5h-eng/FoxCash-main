package com.foxcash.domain.exception;

/**
 * Falha na chamada à Bot API do Telegram (Spec 74) — token inválido, rede,
 * resposta {@code ok:false}. A integração converte em {@code ERROR} com
 * auditoria {@code BOT_INTEGRATION_ERROR}.
 */
public class TelegramApiException extends RuntimeException {

    public TelegramApiException(String message) {
        super(message);
    }

    public TelegramApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
