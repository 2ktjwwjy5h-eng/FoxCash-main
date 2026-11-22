package com.foxcash.domain.port.out;

import com.foxcash.domain.model.delivery.DeliveryActionExec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência das ações de entrega em EXECUÇÃO (Spec 49) —
 * o snapshot das ações do plano na data do pagamento.
 */
public interface PlanDeliveryActionExecRepository {

    DeliveryActionExec save(DeliveryActionExec exec);

    List<DeliveryActionExec> saveAll(List<DeliveryActionExec> execs);

    /** Ações da entrega em ordem de execução (seq asc). */
    List<DeliveryActionExec> findByDeliveryIdOrderBySeq(UUID deliveryId);

    /**
     * Mesmo que {@link #findByDeliveryIdOrderBySeq}, com LOCK pessimista de
     * escrita — usado APENAS na conclusão/retry (transação de escrita) para
     * serializar conclusões concorrentes da mesma entrega (1 execução, 1
     * auditoria). Não usar em leituras read-only.
     */
    List<DeliveryActionExec> findByDeliveryIdOrderBySeqForUpdate(UUID deliveryId);

    /** Busca por id E delivery (404 cross-loja). */
    Optional<DeliveryActionExec> findByIdAndDeliveryId(UUID execId, UUID deliveryId);
}
