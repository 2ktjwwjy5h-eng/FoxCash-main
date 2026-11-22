package com.foxcash.domain.port.out;

import com.foxcash.domain.model.plan.Plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de planos da loja (Spec 49). Os métodos são por
 * {@code storeId} (a loja implica o merchant — o principal da sessão/API key) —
 * nunca por id solto, para não vazar dados entre lojistas.
 */
public interface PlanRepository {

    Plan save(Plan plan);

    /** Busca por id E store (404 cross-loja — nunca revela existência). */
    Optional<Plan> findByIdAndStoreId(UUID planId, UUID storeId);

    /** Planos da loja (mais recentes primeiro). */
    List<Plan> findByStoreId(UUID storeId);

    /** Planos ATIVOS da loja (storefront — Spec 48/49). */
    List<Plan> findActiveByStoreId(UUID storeId);
}
