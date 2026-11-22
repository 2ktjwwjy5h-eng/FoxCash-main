package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableSearchQuery;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.port.out.ReceivableRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.ReceivableJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.ReceivableMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ReceivableJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link ReceivableRepository} (Spec 33). Filtros
 * dinamicos via Specifications (mesmo padrao do dashboard/admin).
 */
@Repository
public class ReceivablePersistenceAdapter implements ReceivableRepository {

    private static final List<ReceivableStatus> OPEN_STATUSES =
            List.of(ReceivableStatus.OPEN, ReceivableStatus.PARTIAL);

    private final ReceivableJpaRepository jpaRepository;

    public ReceivablePersistenceAdapter(ReceivableJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public Receivable save(Receivable receivable) {
        return ReceivableMapper.toDomain(jpaRepository.save(ReceivableMapper.toEntity(receivable)));
    }

    @Override
    public Optional<Receivable> findById(UUID id) {
        return jpaRepository.findById(id).map(ReceivableMapper::toDomain);
    }

    @Override
    public Optional<Receivable> findOpenByOrigin(UUID merchantId, ReceivableOrigin origin, UUID referenceId) {
        return jpaRepository.findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
                        merchantId, origin, referenceId, OPEN_STATUSES)
                .map(ReceivableMapper::toDomain);
    }

    @Override
    public List<Receivable> findOpenByOrigins(List<ReceivableOrigin> origins) {
        return jpaRepository.findByOriginInAndStatusIn(origins, OPEN_STATUSES).stream()
                .map(ReceivableMapper::toDomain)
                .toList();
    }

    @Override
    public boolean hasOpenDebt(UUID merchantId) {
        return jpaRepository.existsByMerchantIdAndStatusIn(merchantId, OPEN_STATUSES);
    }

    @Override
    @Transactional
    public void markPaidIfOpen(UUID merchantId, ReceivableOrigin origin, UUID referenceId, Instant now) {
        jpaRepository.findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
                        merchantId, origin, referenceId, OPEN_STATUSES)
                .ifPresent(entity -> {
                    entity.setStatus(ReceivableStatus.PAID);
                    entity.setPaidAmount(entity.getAmount());
                    entity.setPaidAt(now);
                    entity.setUpdatedAt(now);
                    jpaRepository.save(entity);
                });
    }

    @Override
    @Transactional
    public Receivable upsertOpen(UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                                 Money remainingDebt, Instant now) {
        Optional<ReceivableJpaEntity> existing = jpaRepository
                .findByMerchantIdAndOriginAndReferenceIdAndStatusIn(merchantId, origin, referenceId, OPEN_STATUSES);
        if (existing.isPresent()) {
            ReceivableJpaEntity entity = existing.get();
            // Re-cobranca parcial (Spec 33): amount (divida total) permanece o
            // valor ORIGINAL da abertura; paid_amount acumula o que foi pago
            // CONTRA esta divida formal. O invariante do dominio
            // remaining() = amount - paid_amount reflete o saldo devedor atual
            // (invoice.outstanding()). Antes o amount era reduzido junto com o
            // paid_amount, tornando remaining() negativo e corrompendo a
            // consulta do master e o write-off (perda negativa) — bug contabil
            // corrigido em 2026-08-05.
            BigDecimal previousRemaining = entity.getAmount().subtract(entity.getPaidAmount());
            BigDecimal paidNow = previousRemaining.subtract(remainingDebt.amount());
            if (paidNow.signum() > 0) {
                entity.setPaidAmount(entity.getPaidAmount().add(paidNow));
                entity.setStatus(ReceivableStatus.PARTIAL);
            }
            entity.setUpdatedAt(now);
            return ReceivableMapper.toDomain(jpaRepository.save(entity));
        }
        Receivable receivable = Receivable.create(merchantId, origin, referenceId, remainingDebt, now);
        return ReceivableMapper.toDomain(jpaRepository.save(ReceivableMapper.toEntity(receivable)));
    }

    @Override
    public PageResult<Receivable> search(ReceivableSearchQuery query) {
        Page<ReceivableJpaEntity> page = jpaRepository.findAll(
                spec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Receivable> items = page.getContent().stream().map(ReceivableMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    private Specification<ReceivableJpaEntity> spec(ReceivableSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
            }
            if (query.origin() != null) {
                predicates.add(cb.equal(root.get("origin"), query.origin()));
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
}
