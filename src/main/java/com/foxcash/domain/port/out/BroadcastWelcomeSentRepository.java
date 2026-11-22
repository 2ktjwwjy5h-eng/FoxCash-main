package com.foxcash.domain.port.out;

import java.util.UUID;

/**
 * Porta de saída: idempotência do modo WELCOME (Spec 79/D6) — registra
 * {@code (chat_id, user_id)} já atendidos pela mensagem de boas-vindas para
 * que o evento de entrada duplicado (Telegram re-envia {@code chat_member})
 * não dispare 2×. UNIQUE {@code (chat_id, user_id)} na tabela.
 */
public interface BroadcastWelcomeSentRepository {

    /** Já atendeu esse usuário nesse chat? (idempotência). */
    boolean existsByChatIdAndUserId(String chatId, String userId);

    /** Registra o atendimento (raça com a UNIQUE — quem perde é no-op). */
    void record(UUID broadcastId, String chatId, String userId);
}
