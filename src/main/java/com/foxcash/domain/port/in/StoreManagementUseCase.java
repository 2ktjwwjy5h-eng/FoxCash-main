package com.foxcash.domain.port.in;

import com.foxcash.domain.model.store.Store;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão da loja do vendedor pelo portal (Spec 48).
 *
 * <p>1 loja por merchant (decisão de produto). {@code create} → 409 se já
 * existe ({@code StoreAlreadyExistsException}); {@code get}/{@code update} →
 * 404 se não existe ({@code StoreNotFoundException}). Tudo por merchantId do
 * principal — nunca por id solto.
 */
public interface StoreManagementUseCase {

    /** Loja do lojista (404 {@code store_not_found} sem loja). */
    Store get(UUID merchantId);

    /** Cria a loja do lojista (201; 409 {@code store_already_exists} se já existe). */
    Store create(UUID merchantId, SaveStoreCommand command);

    /**
     * Atualiza os campos informados (não-nulos) da loja do lojista
     * (200; 404 {@code store_not_found} sem loja).
     */
    Store update(UUID merchantId, SaveStoreCommand command);

    /** Dados de identidade da loja (campos nulos = não alterar no update). */
    record SaveStoreCommand(
            String name,
            String logoUrl,
            String bannerUrl,
            String description,
            List<Store.SocialLink> socialLinks,
            String color,
            String emoji,
            String title,
            String footer) {
    }
}
