package com.foxcash.domain.exception;

/**
 * Falha ao enviar mensagem pelo provedor do bot no painel do CRM (Spec 83 D5) —
 * 502 {@code message_send_failed} (o provedor rejeitou; a mensagem NÃO é
 * persistida como enviada). O caso específico de bloqueio é
 * {@link LeadBlockedException} (409 {@code lead_blocked} + lead BLOQUEADO).
 */
public class MessageSendFailedException extends RuntimeException {

    public MessageSendFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
