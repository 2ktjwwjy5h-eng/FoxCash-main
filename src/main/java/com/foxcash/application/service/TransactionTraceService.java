package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.port.out.BotFunnelEventRepository;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: trace de transacao (Spec 125) — monta a jornada completa de uma
 * cobranca (Bot → Gateway → PSP → Ledger → Webhook) agregando as fontes
 * existentes por {@code transaction.id}: trilha de atividade (Spec 41), ledger
 * do wallet/master (Specs 27/34), funil do bot (Spec 75) e entrega do webhook
 * no outbox (Spec 35, fase 2). Read-only — ferramenta de suporte (ROLE_MASTER).
 */
@Service
public class TransactionTraceService {

    /** Referência de origem dos eventos transacionais no outbox (Spec 125). */
    public static final String ENTITY_TYPE_TRANSACTION = "TRANSACTION";

    /** Etapa da jornada exibida no trace (ordem = canônica do merge). */
    public enum TraceStage { BOT, GATEWAY, PSP, LEDGER, WEBHOOK }

    /** Item da linha do tempo (uma etapa que REALMENTE ocorreu). */
    public record TraceItem(Instant at, TraceStage stage, String event, Map<String, Object> detail) {
    }

    /** Resultado do trace: a transação + a linha do tempo. */
    public record TransactionTrace(PixTransaction transaction, List<TraceItem> timeline) {
    }

    private final TransactionRepository transactionRepository;
    private final PlatformActivityRepository activityRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final MasterLedgerRepository masterLedgerRepository;
    private final BotFunnelEventRepository funnelRepository;
    private final NotificationOutboxPort outbox;
    private final ObjectMapper objectMapper;

    public TransactionTraceService(TransactionRepository transactionRepository,
                                   PlatformActivityRepository activityRepository,
                                   WalletLedgerRepository walletLedgerRepository,
                                   MasterLedgerRepository masterLedgerRepository,
                                   BotFunnelEventRepository funnelRepository,
                                   NotificationOutboxPort outbox,
                                   ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.activityRepository = activityRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.masterLedgerRepository = masterLedgerRepository;
        this.funnelRepository = funnelRepository;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Monta o trace da transação resolvida por {@code ref}: UUID interno →
     * {@code externalReference} (txid do PSP) → {@code idempotencyKey} (do bot).
     * Transação inexistente → {@link TransactionNotFoundException} (404).
     */
    public TransactionTrace trace(String ref) {
        PixTransaction tx = resolve(ref);
        List<TraceItem> timeline = new ArrayList<>();

        // Trilha de atividade (Spec 41): eventos por entidade (índice V73).
        PageResult<PlatformActivity> activities = activityRepository.query(
                new PlatformActivityQuery(null, null, null, null, null, 0, 100,
                        ENTITY_TYPE_TRANSACTION, tx.id()));
        for (PlatformActivity activity : activities.items()) {
            timeline.add(new TraceItem(activity.createdAt(), stageOf(activity.action()),
                    activity.action().name(), parseDetails(activity.details())));
        }

        // Ledger do wallet (Spec 27): o dinheiro que entrou/saiu do lojista.
        for (WalletLedgerEntry entry : walletLedgerRepository
                .findByReferenceTypeAndReferenceId(ENTITY_TYPE_TRANSACTION, tx.id())) {
            timeline.add(new TraceItem(entry.createdAt(), TraceStage.LEDGER, entry.type().name(),
                    Map.of("amount", entry.amount().amount(),
                            "balanceAfter", entry.balanceAfter().amount())));
        }

        // Ledger do master (Spec 34): receita da plataforma da mesma origem.
        for (MasterLedgerEntry entry : masterLedgerRepository
                .findByReferenceTypeAndReferenceId(ENTITY_TYPE_TRANSACTION, tx.id())) {
            timeline.add(new TraceItem(entry.createdAt(), TraceStage.LEDGER, entry.type().name(),
                    Map.of("amount", entry.amount().amount())));
        }

        // Funil do bot (Spec 75): o bot viu a venda (lead → checkout → paid).
        for (BotFunnelEvent funnel : funnelRepository.findByTransactionId(tx.id())) {
            timeline.add(new TraceItem(funnel.occurredAt(), TraceStage.BOT, funnel.event().name(),
                    Map.of("botId", funnel.botId().toString(),
                            "platformUserId", String.valueOf(funnel.platformUserId()))));
        }

        // Outbox (Spec 35, fase 2): a entrega do webhook (status/attempts/erro).
        for (OutboxNotification notification : outbox.findByEntity(ENTITY_TYPE_TRANSACTION, tx.id())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", notification.status());
            detail.put("attempts", notification.attempts());
            if (notification.lastError() != null) {
                detail.put("lastError", notification.lastError());
            }
            if (notification.nextAttemptAt() != null) {
                detail.put("nextAttemptAt", notification.nextAttemptAt().toString());
            }
            timeline.add(new TraceItem(notification.createdAt(), TraceStage.WEBHOOK,
                    notification.type(), detail));
        }

        // Merge ordenado: por timestamp; empates pela ordem canônica da jornada.
        timeline.sort(Comparator.comparing(TraceItem::at)
                .thenComparingInt(item -> item.stage().ordinal()));
        return new TransactionTrace(tx, timeline);
    }

    private PixTransaction resolve(String ref) {
        Optional<PixTransaction> byId = tryParseUuid(ref).flatMap(transactionRepository::findById);
        if (byId.isPresent()) {
            return byId.get();
        }
        return transactionRepository.findByExternalReference(ref)
                .or(() -> transactionRepository.findByIdempotencyKey(ref))
                .orElseThrow(() -> new TransactionNotFoundException(ref));
    }

    /** Etapa da jornada conforme a ação registrada na trilha (Spec 125/D5). */
    private TraceStage stageOf(PlatformAction action) {
        return switch (action) {
            case SALE_PAID, SALE_REFUNDED -> TraceStage.PSP;
            default -> TraceStage.GATEWAY;
        };
    }

    private Optional<UUID> tryParseUuid(String ref) {
        try {
            return Optional.of(UUID.fromString(ref));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Details da activity é JSON mascarado na origem — devolve como mapa. */
    private Map<String, Object> parseDetails(String details) {
        if (details == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(details, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of("raw", details);
        }
    }
}
