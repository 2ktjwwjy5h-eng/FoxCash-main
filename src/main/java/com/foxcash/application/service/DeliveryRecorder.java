package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.out.PlanDeliveryActionExecRepository;
import com.foxcash.domain.port.out.PlanDeliveryActionRepository;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Grava a entrega do plano quando a transação do plano é APROVADA (Spec 49) —
 * chamado dentro da MESMA transação do webhook ({@code ProcessPixWebhookService}).
 *
 * <p>Cria {@code plan_deliveries} PENDING com o destinatário
 * ({@code platform} + {@code platform_user_id}, da transação — criptografado em
 * repouso) e copia as ações do plano para {@code plan_delivery_actions_exec}
 * (snapshot, todas PENDING). Replay de PAID → no-op (unique na transação);
 * plano sem ações → entrega DELIVERED direto.
 */
@Component
public class DeliveryRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRecorder.class);

    private final StoreRepository storeRepository;
    private final PlanRepository planRepository;
    private final PlanDeliveryActionRepository actionRepository;
    private final PlanDeliveryRepository deliveryRepository;
    private final PlanDeliveryActionExecRepository execRepository;
    private final Clock clock;

    public DeliveryRecorder(StoreRepository storeRepository,
                            PlanRepository planRepository,
                            PlanDeliveryActionRepository actionRepository,
                            PlanDeliveryRepository deliveryRepository,
                            PlanDeliveryActionExecRepository execRepository,
                            Clock clock) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.actionRepository = actionRepository;
        this.deliveryRepository = deliveryRepository;
        this.execRepository = execRepository;
        this.clock = clock;
    }

    /**
     * Grava a entrega da transação aprovada (no-op quando não é compra de plano
     * ou quando a entrega já existe — replay). Nunca lança para não quebrar o
     * pagamento: falha é logada (a entrega é recuperável pela reconciliação).
     */
    @Transactional
    public void recordFor(PixTransaction transaction) {
        UUID planId = transaction.planId();
        if (planId == null || transaction.platform() == null
                || transaction.platformUserId() == null) {
            return; // cobrança avulsa — sem entrega de plano
        }
        if (deliveryRepository.findByTransactionId(transaction.id()).isPresent()) {
            return; // replay de PAID — entrega já criada (idempotente)
        }
        UUID storeId = storeRepository.findByMerchantId(transaction.merchantId())
                .orElseThrow(StoreNotFoundException::new)
                .id();
        Plan plan = planRepository.findByIdAndStoreId(planId, storeId).orElse(null);
        if (plan == null) {
            // Defensivo: o FK RESTRICT de pix_transactions.plan_id impede a
            // remoção de um plano referenciado; se ocorrer, não quebra o pagamento.
            log.warn("Entrega ignorada: plano {} nao encontrado para a transacao {}",
                    planId, transaction.id());
            return;
        }
        try {
            PlanDelivery delivery = PlanDelivery.create(storeId, planId, transaction.id(),
                    transaction.platform(), transaction.platformUserId(),
                    transaction.buyerIdHash(), clock.instant(),
                    transaction.termsAcceptedAt(), transaction.buyerIp(), transaction.buyerDevice());
            List<DeliveryAction> actions = actionRepository.findByPlanIdOrderBySeq(planId);
            if (actions.isEmpty()) {
                // Plano sem ações → entrega no-op DELIVERED direto.
                delivery.markDelivered(clock.instant());
                deliveryRepository.save(delivery);
                return;
            }
            deliveryRepository.save(delivery);
            List<DeliveryActionExec> execs = actions.stream()
                    .map(a -> DeliveryActionExec.create(delivery.id(), a.seq(), a.actionType(),
                            a.payload()))
                    .toList();
            execRepository.saveAll(execs);
        } catch (DataIntegrityViolationException e) {
            // Corrida: a entrega já foi criada por outro processamento (unique
            // uq_plan_deliveries_transaction) — no-op idempotente.
            log.debug("Entrega ja existente para a transacao {} (replay/corrida)", transaction.id());
        }
    }
}
