package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.audit.AuditSearchQuery;
import com.foxcash.domain.model.audit.MasterAuditEntry;
import com.foxcash.domain.port.out.MasterAuditRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterAuditJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterAuditMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterAuditJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador JPA do port {@link MasterAuditRepository} (Spec 37). Append-only:
 * expoe apenas insert e consulta. Filtros dinamicos via Specifications.
 */
@Repository
public class MasterAuditPersistenceAdapter implements MasterAuditRepository {

    private final MasterAuditJpaRepository jpaRepository;

    public MasterAuditPersistenceAdapter(MasterAuditJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MasterAuditEntry save(MasterAuditEntry entry) {
        return MasterAuditMapper.toDomain(jpaRepository.save(MasterAuditMapper.toEntity(entry)));
    }

    @Override
    public PageResult<MasterAuditEntry> search(AuditSearchQuery query) {
        Page<MasterAuditJpaEntity> page = jpaRepository.findAll(
                spec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<MasterAuditEntry> items = page.getContent().stream()
                .map(MasterAuditMapper::toDomain)
                .toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    private Specification<MasterAuditJpaEntity> spec(AuditSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            if (query.action() != null) {
                predicates.add(cb.equal(root.get("action"), query.action()));
            }
            if (query.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), query.actorId()));
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
