package com.foxcash.domain.exception;

/**
 * Falha na chamada à API REST do Discord (Spec 74) — token inválido, rede,
 * erro HTTP. A integração converte em {@code ERROR} com auditoria.
 */
public class DiscordApiException extends RuntimeException {

    public DiscordApiException(String message) {
        super(message);
    }

    public DiscordApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
