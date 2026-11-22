package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.BroadcastMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.BroadcastJpaRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link BroadcastRepository} (Spec 79).
 */
@Repository
public class BroadcastPersistenceAdapter implements BroadcastRepository {

    private final BroadcastJpaRepository jpaRepository;

    public BroadcastPersistenceAdapter(BroadcastJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Broadcast save(Broadcast broadcast) {
        return BroadcastMapper.toDomain(jpaRepository.save(BroadcastMapper.toEntity(broadcast)));
    }

    @Override
    public Optional<Broadcast> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId).map(BroadcastMapper::toDomain);
    }

    @Override
    public Optional<Broadcast> findById(UUID id) {
        return jpaRepository.findById(id).map(BroadcastMapper::toDomain);
    }

    @Override
    public List<Broadcast> findByMerchantId(UUID merchantId, BroadcastMode mode, BroadcastStatus status) {
        return jpaRepository.findByMerchantId(merchantId, mode, status)
                .stream().map(BroadcastMapper::toDomain).toList();
    }

    @Override
    public List<Broadcast> findByMerchantIdAndBotIds(UUID merchantId, List<UUID> botIds,
                                                     BroadcastMode mode, BroadcastStatus status) {
        // Spec 124 (filtro rígido por bot-set): store_bot_id IN :botIds com join
        // por lojista — lista vazia → vazio (header inválido nunca vaza).
        return jpaRepository.findByMerchantIdAndBotIds(merchantId, botIds, mode, status)
                .stream().map(BroadcastMapper::toDomain).toList();
    }

    @Override
    public PageResult<Broadcast> searchMaster(MasterBroadcastSearchQuery query) {
        Page<BroadcastJpaEntity> page = jpaRepository.findAll(
                masterSpec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Broadcast> items = page.getContent().stream().map(BroadcastMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    /**
     * Filtros dinâmicos cross-merchant (Spec 89) via Specification — mesmo padrão
     * do {@code MasterAuditPersistenceAdapter} (predicates só entram quando o
     * filtro é não-nulo; sem {@code IS NULL OR} — evita o erro do Postgres de
     * inferência de tipo). O filtro por {@code merchantId} usa subquery sobre
     * {@code store_bots} (o broadcast não tem FK direta para lojista).
     */
    private Specification<BroadcastJpaEntity> masterSpec(MasterBroadcastSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                Subquery<UUID> sub = cq.subquery(UUID.class);
                Root<StoreBotJpaEntity> bot = sub.from(StoreBotJpaEntity.class);
                sub.select(bot.get("id"));
                sub.where(cb.equal(bot.get("merchantId"), query.merchantId()));
                predicates.add(root.get("storeBotId").in(sub));
            }
            if (query.botId() != null) {
                predicates.add(cb.equal(root.get("storeBotId"), query.botId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.mode() != null) {
                predicates.add(cb.equal(root.get("mode"), query.mode()));
            }
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Override
    public List<Broadcast> findDue(UUID merchantId, Instant dueBefore) {
        return jpaRepository.findDue(merchantId, dueBefore)
                .stream().map(BroadcastMapper::toDomain).toList();
    }

    @Override
    public List<Broadcast> findWelcomeForChat(UUID storeBotId, String chatId) {
        return jpaRepository.findWelcomeForChat(storeBotId, chatId)
                .stream().map(BroadcastMapper::toDomain).toList();
    }

    @Override
    public List<Broadcast> findCompletedReposts() {
        return jpaRepository.findCompletedReposts()
                .stream().map(BroadcastMapper::toDomain).toList();
    }

    @Override
    public void delete(Broadcast broadcast) {
        jpaRepository.delete(BroadcastMapper.toEntity(broadcast));
    }
}
