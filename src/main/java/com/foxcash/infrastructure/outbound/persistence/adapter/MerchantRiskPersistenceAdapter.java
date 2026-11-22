package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantRiskJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantRiskMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantRiskJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link RiskRepository} (Spec 16).
 */
@Repository
public class MerchantRiskPersistenceAdapter implements RiskRepository {

    private final MerchantRiskJpaRepository jpaRepository;

    public MerchantRiskPersistenceAdapter(MerchantRiskJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MerchantRiskSnapshot save(MerchantRiskSnapshot snapshot) {
        return MerchantRiskMapper.toDomain(jpaRepository.save(MerchantRiskMapper.toEntity(snapshot)));
    }

    @Override
    public PageResult<MerchantRiskSnapshot> search(RiskSearchQuery query) {
        Page<MerchantRiskJpaEntity> page = jpaRepository.findAll(
                spec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "computedAt")));
        List<MerchantRiskSnapshot> items = page.getContent().stream()
                .map(MerchantRiskMapper::toDomain)
                .toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public Optional<MerchantRiskSnapshot> latestByMerchant(UUID merchantId) {
        return jpaRepository.findTopByMerchantIdOrderByComputedAtDesc(merchantId)
                .map(MerchantRiskMapper::toDomain);
    }

    private Specification<MerchantRiskJpaEntity> spec(RiskSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
