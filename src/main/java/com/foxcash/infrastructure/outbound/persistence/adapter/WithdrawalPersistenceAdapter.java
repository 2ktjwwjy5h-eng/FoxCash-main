package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalSearchQuery;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.WithdrawalJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.WithdrawalMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.WithdrawalJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link WithdrawalRepository} (Spec 14).
 * Filtros dinâmicos via Specifications (mesmo padrão do dashboard/admin).
 */
@Repository
public class WithdrawalPersistenceAdapter implements WithdrawalRepository {

    private final WithdrawalJpaRepository jpaRepository;

    public WithdrawalPersistenceAdapter(WithdrawalJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Withdrawal save(Withdrawal withdrawal) {
        return WithdrawalMapper.toDomain(jpaRepository.save(WithdrawalMapper.toEntity(withdrawal)));
    }

    @Override
    public Optional<Withdrawal> findById(UUID id) {
        return jpaRepository.findById(id).map(WithdrawalMapper::toDomain);
    }

    @Override
    public List<Withdrawal> findByMerchantId(UUID merchantId, int limit) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, limit)).stream()
                .map(WithdrawalMapper::toDomain)
                .toList();
    }

    @Override
    public PageResult<Withdrawal> search(WithdrawalSearchQuery query) {
        Page<WithdrawalJpaEntity> page = jpaRepository.findAll(
                spec(query),
                PageRequest.of(query.page(), query.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Withdrawal> items = page.getContent().stream().map(WithdrawalMapper::toDomain).toList();
        return PageResult.of(items, query.page(), query.size(), page.getTotalElements());
    }

    @Override
    public BigDecimal sumCompletedAmountByMerchantIdBetween(UUID merchantId, Instant from, Instant to) {
        return jpaRepository.sumCompletedAmountByMerchantIdBetween(merchantId, from, to);
    }

    @Override
    public BigDecimal sumAmountAndFeeByMerchantAndTypeCreatedSince(UUID merchantId, WithdrawalType type, Instant since) {
        return jpaRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(merchantId, type, since);
    }

    @Override
    public Optional<Withdrawal> findByExternalReferenceForUpdate(String externalReference) {
        return jpaRepository.findByExternalReferenceForUpdate(externalReference)
                .map(WithdrawalMapper::toDomain);
    }

    @Override
    public Optional<Withdrawal> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(WithdrawalMapper::toDomain);
    }

    @Override
    public List<Withdrawal> findProcessingOlderThan(Instant cutoff) {
        return jpaRepository.findProcessingOlderThan(cutoff).stream()
                .map(WithdrawalMapper::toDomain)
                .toList();
    }

    private Specification<WithdrawalJpaEntity> spec(WithdrawalSearchQuery query) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query.merchantId() != null) {
                predicates.add(cb.equal(root.get("merchantId"), query.merchantId()));
            }
            if (query.status() != null) {
                predicates.add(cb.equal(root.get("status"), query.status()));
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
