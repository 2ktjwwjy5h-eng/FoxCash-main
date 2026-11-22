package com.foxcash.domain.model.crm;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem persistida do chat do CRM (Spec 83) — histórico por lead
 * ({@code lead_id}, {@code bot_id}) com remetente {@link ChatSenderType}.
 * O Live Chat do painel exibe as bolhas a partir desta trilha; o envio do
 * painel persiste {@code ADMIN} APÓS sucesso no provedor (Spec 83 D5).
 */
public record ChatMessage(
        UUID id,
        UUID leadId,
        UUID botId,
        ChatSenderType senderType,
        String textContent,
        String mediaUrl,
        boolean read,
        Instant createdAt) {

    public ChatMessage {
        Objects.requireNonNull(leadId, "leadId must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(senderType, "senderType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if ((textContent == null || textContent.isBlank()) && (mediaUrl == null || mediaUrl.isBlank())) {
            throw new IllegalArgumentException("mensagem exige texto ou midia");
        }
    }

    /** Nova mensagem do cliente recebida via webhook/listener (Spec 83 D7). */
    public static ChatMessage inbound(UUID leadId, UUID botId, String text, String mediaUrl, Instant now) {
        return new ChatMessage(UUID.randomUUID(), leadId, botId, ChatSenderType.CLIENT, text, mediaUrl, false, now);
    }

    /** Nova mensagem enviada pelo painel (após sucesso no provedor — Spec 83 D5). */
    public static ChatMessage outbound(UUID leadId, UUID botId, String text, String mediaUrl, Instant now) {
        return new ChatMessage(UUID.randomUUID(), leadId, botId, ChatSenderType.ADMIN, text, mediaUrl, false, now);
    }

    /** Restaura do banco (o dado persistido é confiável). */
    public static ChatMessage restore(UUID id, UUID leadId, UUID botId, ChatSenderType senderType,
                                      String textContent, String mediaUrl, boolean read, Instant createdAt) {
        return new ChatMessage(id, leadId, botId, senderType, textContent, mediaUrl, read, createdAt);
    }
}
