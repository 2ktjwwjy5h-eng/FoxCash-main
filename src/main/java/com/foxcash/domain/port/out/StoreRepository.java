package com.foxcash.domain.port.out;

import com.foxcash.domain.model.store.Store;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia da loja do vendedor (Spec 48).
 *
 * <p>1 loja por merchant nesta versao (decisao de produto; o schema tem
 * {@code merchant_id} UNIQUE). Os metodos sao por merchant (o principal da
 * sessao/API key) — nunca por id solto, para nao vazar dados entre lojistas.
 */
public interface StoreRepository {

    Store save(Store store);

    Optional<Store> findByMerchantId(UUID merchantId);

    /** Busca por id E merchant (404 cross-loja — nunca revela existencia). */
    Optional<Store> findByIdAndMerchantId(UUID storeId, UUID merchantId);

    boolean existsByMerchantId(UUID merchantId);
}
