package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.PlatformActivityJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.PlatformActivityMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PlatformActivityJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador JPA do port {@link PlatformActivityRepository} (Spec 41) — consulta
 * paginada com filtros via Specifications (mesmo padrão do admin dashboard).
 */
@Repository
public class PlatformActivityPersistenceAdapter implements PlatformActivityRepository {

    private final PlatformActivityJpaRepository jpaRepository;

    public PlatformActivityPersistenceAdapter(PlatformActivityJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PlatformActivity save(PlatformActivity activity) {
        return PlatformActivityMapper.toDomain(
                jpaRepository.save(PlatformActivityMapper.toEntity(activity)));
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jpaRepository.deleteOlderThan(cutoff);
    }

    @Override
    public PageResult<PlatformActivity> query(PlatformActivityQuery query) {
        Page<PlatformActivityJpaEntity> page = jpaRepository.findAll(
                spec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<PlatformActivity> items = page.getContent().stream()
                .map(PlatformActivityMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    private Specification<PlatformActivityJpaEntity> spec(PlatformActivityQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            }
            if (query.actorType() != null) {
                predicates.add(cb.equal(root.get("actorType"), query.actorType()));
            }
            if (query.action() != null) {
                predicates.add(cb.equal(root.get("action"), query.action()));
            }
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            // Spec 125 (trace): filtro por entidade de origem — usado pelo
            // TransactionTraceService para listar a jornada de UMA transação.
            if (query.entityType() != null) {
                predicates.add(cb.equal(root.get("entityType"), query.entityType()));
            }
            if (query.entityId() != null) {
                predicates.add(cb.equal(root.get("entityId"), query.entityId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
