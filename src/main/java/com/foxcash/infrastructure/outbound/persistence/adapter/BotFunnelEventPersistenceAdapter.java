package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.domain.model.botfunnel.FunnelEvent;
import com.foxcash.domain.port.out.BotFunnelEventRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.BotFunnelEventMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.BotFunnelEventJpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link BotFunnelEventRepository} (Spec 75) — trilha
 * append-only de eventos de funil.
 */
@Repository
public class BotFunnelEventPersistenceAdapter implements BotFunnelEventRepository {

    private final BotFunnelEventJpaRepository jpaRepository;

    public BotFunnelEventPersistenceAdapter(BotFunnelEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(BotFunnelEvent event) {
        jpaRepository.save(BotFunnelEventMapper.toEntity(event));
    }

    @Override
    public Optional<BotFunnelEvent> findByBotIdAndTransactionId(UUID botId, UUID transactionId) {
        return jpaRepository.findByBotIdAndTransactionId(botId, transactionId)
                .map(BotFunnelEventMapper::toDomain);
    }

    @Override
    public List<BotFunnelEvent> findByTransactionId(UUID transactionId) {
        return jpaRepository.findByTransactionId(transactionId)
                .stream().map(BotFunnelEventMapper::toDomain).toList();
    }

    @Override
    public Optional<BotFunnelEvent> findByBotIdAndTransactionIdAndEvent(UUID botId,
                                                                        UUID transactionId,
                                                                        FunnelEvent event) {
        return jpaRepository.findByBotIdAndTransactionIdAndEvent(botId, transactionId, event)
                .map(BotFunnelEventMapper::toDomain);
    }

    @Override
    public long countByBotIdAndEvent(UUID botId, FunnelEvent event) {
        return jpaRepository.countByBotIdAndEvent(botId, event);
    }

    @Override
    public long countByMerchantIdAndEventBetween(UUID merchantId, FunnelEvent event,
                                                 Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndEventBetween(merchantId, event, from, to);
    }

    @Override
    public Map<UUID, Long> countGroupedByBotId(List<UUID> botIds, FunnelEvent event,
                                               Instant from, Instant to) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        if (botIds.isEmpty()) {
            return result;
        }
        for (Object[] row : jpaRepository.countGroupedByBotId(botIds, event, from, to)) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }
}
