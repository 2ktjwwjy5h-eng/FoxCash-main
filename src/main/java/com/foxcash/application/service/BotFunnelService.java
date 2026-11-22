package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.botfunnel.FunnelEvent;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.out.BotFunnelEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Aplicação: funil por bot (Spec 75) — {@code lead → checkout_started → paid}
 * por {@code (bot_id, platform_user_id)}. Trilha append-only; a métrica de
 * venda lê o status da transação (fonte oficial), nunca estes eventos como
 * dinheiro.
 *
 * <p><b>Idempotência</b>: {@code recordLead} e {@code recordCheckout} são
 * naturalmente idempotentes (UNIQUE de {@code bot_leads} / no-op quando não há
 * {@code botId}); {@code recordPaid} é idempotente por {@code (bot_id,
 * transaction_id)} — chamado na MESMA transação do PAID (Spec 23), o replay do
 * webhook não duplica.
 *
 * <p><b>Spec 81/D5.1</b>: quando o evento é NOVO, dispara o tracking
 * ({@code checkout_started → InitiateCheckout}, {@code paid → Purchase}) na
 * mesma transação — replay não re-dispara (o evento já existe).
 */
@Service
public class BotFunnelService {

    private final BotFunnelEventRepository funnelRepository;
    private final TrackingDispatchService trackingDispatch;
    private final LeadStatusDerivationService statusDerivation;
    private final Clock clock;

    public BotFunnelService(BotFunnelEventRepository funnelRepository,
                            TrackingDispatchService trackingDispatch,
                            LeadStatusDerivationService statusDerivation,
                            Clock clock) {
        this.funnelRepository = funnelRepository;
        this.trackingDispatch = trackingDispatch;
        this.statusDerivation = statusDerivation;
        this.clock = clock;
    }

    /**
     * Grava {@code checkout_started} (purchase com {@code botId}) — chamado na
     * MESMA transação da compra (Spec 75/T1.2). Idempotente por construção: o
     * purchase já é idempotente pela {@code idempotencyKey} (Spec 28), o replay
     * reusa a MESMA transação e não re-cria o evento (o evento fica 1× por
     * transação).
     */
    @Transactional
    public void recordCheckout(UUID botId, String platformUserId, UUID transactionId, Money amount) {
        if (botId == null || transactionId == null || platformUserId == null
                || platformUserId.isBlank()) {
            return; // cobrança sem atribuição de bot — zero regressão
        }
        // Replay com a MESMA transação (retry da idempotencyKey) não duplica.
        if (funnelRepository.findByBotIdAndTransactionId(botId, transactionId).isPresent()) {
            return;
        }
        funnelRepository.save(BotFunnelEvent.create(botId, platformUserId,
                FunnelEvent.CHECKOUT_STARTED, transactionId, clock.instant()));
        trackingDispatch.onCheckout(botId, platformUserId, transactionId, amount);
        // Spec 83 D4: deriva o status do CRM na MESMA transação (PENDENTE).
        statusDerivation.onCheckoutStarted(botId, platformUserId);
    }

    /**
     * Grava {@code paid} na MESMA transação que marca a transação {@code PAID}
     * (Spec 23). Idempotente por {@code (bot_id, transaction_id)}: replay do
     * webhook não duplica. Sem {@code bot_id} na transação → no-op (cobrança
     * avulsa sem atribuição de bot).
     */
    @Transactional
    public void recordPaid(PixTransaction transaction) {
        if (transaction == null || transaction.botId() == null
                || transaction.platformUserId() == null
                || transaction.platformUserId().isBlank()) {
            return;
        }
        if (funnelRepository.findByBotIdAndTransactionIdAndEvent(
                transaction.botId(), transaction.id(), FunnelEvent.PAID).isPresent()) {
            return; // replay de PAID — evento já gravado
        }
        Instant occurredAt = transaction.paidAt() != null ? transaction.paidAt() : clock.instant();
        funnelRepository.save(BotFunnelEvent.create(transaction.botId(),
                transaction.platformUserId(), FunnelEvent.PAID, transaction.id(), occurredAt));
        trackingDispatch.onPurchase(transaction.botId(), transaction.platformUserId(),
                transaction.id(), transaction.amount());
        // Spec 83 D4: deriva o status do CRM na MESMA transação (PAGO).
        statusDerivation.onPaid(transaction.botId(), transaction.platformUserId());
    }
}
