package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.crm.ChatMessage;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída: persistência das mensagens do chat do CRM (Spec 83) —
 * histórico por lead, contagem de não lidas e marcação de lida.
 */
public interface ChatMessageRepository {

    ChatMessage save(ChatMessage message);

    /** Histórico do lead paginado (cronológico ascendente — Spec 83 D9). */
    PageResult<ChatMessage> findByLeadId(UUID leadId, int page, int size);

    /** Mensagens de um lead (sem paginação — dreno/uso interno). */
    List<ChatMessage> findAllByLeadId(UUID leadId);

    /** Contagem de mensagens {@code CLIENT} não lidas (indicador do painel). */
    long countByLeadIdAndReadFalse(UUID leadId);

    /** Marca todas as mensagens {@code CLIENT} como lidas (Spec 83 C11). */
    void markAllRead(UUID leadId);
}
