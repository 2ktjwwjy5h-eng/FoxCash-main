package com.foxcash.domain.port.out;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência das mensagens programadas da loja (Spec 51).
 * Por {@code storeId} (implica o merchant) — o bot/API key do lojista dono
 * acessa apenas as próprias mensagens (404 cross-loja).
 */
public interface ScheduledMessageRepository {

    ScheduledMessage save(ScheduledMessage message);

    /** Mensagem do id + loja (404 cross-loja — nunca revela existência). */
    Optional<ScheduledMessage> findByIdAndStoreId(UUID messageId, UUID storeId);

    /** Todas as mensagens programadas da loja (portal). */
    List<ScheduledMessage> findByStoreId(UUID storeId);

    /**
     * Mensagens no estado informado (ex.: SCHEDULED) da loja — a filtragem de
     * vencidas (one-shot por data / cron pelo próximo disparo) acontece no
     * serviço (o cron exige avaliação em Java).
     */
    List<ScheduledMessage> findByStoreIdAndStatus(UUID storeId, ScheduledMessageStatus status);
}
