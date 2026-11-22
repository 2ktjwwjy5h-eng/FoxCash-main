package com.foxcash.domain.port.out;

import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência das mensagens automáticas da loja (Spec 51).
 *
 * <p>Os métodos são por {@code storeId} (loja resolvida pelo merchant do
 * principal) — nunca por id solto, para não vazar dados entre lojistas. O
 * {@code event} é único por loja (unique {@code (store_id, event)} — upsert).
 */
public interface StoreMessageRepository {

    StoreMessage save(StoreMessage message);

    /** Mensagem do id + loja (404 cross-loja — nunca revela existência). */
    Optional<StoreMessage> findByIdAndStoreId(UUID messageId, UUID storeId);

    /** Mensagem da etapa na loja (upsert — 1 por event). */
    Optional<StoreMessage> findByStoreIdAndEvent(UUID storeId, StoreMessageEvent event);

    /** Todas as mensagens da loja (portal). */
    List<StoreMessage> findByStoreId(UUID storeId);

    /** Apenas mensagens ATIVAS (storefront — o bot renderiza o fluxo). */
    List<StoreMessage> findActiveByStoreId(UUID storeId);

    void delete(StoreMessage message);
}
