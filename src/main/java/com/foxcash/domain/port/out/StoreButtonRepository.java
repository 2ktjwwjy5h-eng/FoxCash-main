package com.foxcash.domain.port.out;

import com.foxcash.domain.model.store.StoreButton;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência dos botões do menu da loja (Spec 50).
 *
 * <p>Os métodos são por {@code storeId} (loja resolvida pelo merchant do
 * principal) — nunca por id solto, para não vazar dados entre lojistas.
 * A {@code position} é única por loja (unique {@code (store_id, position)}).
 */
public interface StoreButtonRepository {

    StoreButton save(StoreButton button);

    /**
     * Salva com flush imediato (SynchronizationType.ALWAYS) — necessário na
     * reordenação: a unique {@code (store_id, position)} exige que cada UPDATE
     * seja aplicado no banco antes do próximo (duas passadas: desloca para
     * posições livres, depois move para a ordem final).
     */
    StoreButton saveAndFlush(StoreButton button);

    /** Botão do id + loja (404 cross-loja — nunca revela existência). */
    Optional<StoreButton> findByIdAndStoreId(UUID buttonId, UUID storeId);

    /** Todos os botões da loja em ordem de {@code position} (menu). */
    List<StoreButton> findByStoreIdOrderByPosition(UUID storeId);

    /** Apenas botões ATIVOS em ordem de {@code position} (storefront). */
    List<StoreButton> findActiveByStoreIdOrderByPosition(UUID storeId);

    long countByStoreId(UUID storeId);

    void delete(StoreButton button);
}
