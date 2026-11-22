package com.foxcash.domain.port.out;

import com.foxcash.domain.model.plan.DeliveryAction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência das ações de entrega configuradas no plano
 * (Spec 49), ordenadas por {@code seq}.
 */
public interface PlanDeliveryActionRepository {

    DeliveryAction save(DeliveryAction action);

    /** Ações do plano em ordem de execução (seq asc). */
    List<DeliveryAction> findByPlanIdOrderBySeq(UUID planId);

    /** Busca por id E planId (404 cross-loja — nunca revela existência). */
    Optional<DeliveryAction> findByIdAndPlanId(UUID actionId, UUID planId);

    /** Busca por id (o plano é verificado pelo serviço — rotas por actionId). */
    Optional<DeliveryAction> findById(UUID actionId);

    /** O plano tem alguma ação configurada? (listagem — {@code hasDeliveryActions}). */
    boolean existsByPlanId(UUID planId);

    void delete(DeliveryAction action);
}
