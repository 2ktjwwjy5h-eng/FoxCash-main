package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.port.out.ReconciliationIssueRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.ReconciliationIssueJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.ReconciliationIssueMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ReconciliationIssueJpaRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link ReconciliationIssueRepository} (Spec 30).
 */
@Repository
public class ReconciliationIssuePersistenceAdapter implements ReconciliationIssueRepository {

    private final ReconciliationIssueJpaRepository jpaRepository;

    public ReconciliationIssuePersistenceAdapter(ReconciliationIssueJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ReconciliationIssue open(UUID merchantId, Money expectedBalance, Money actualBalance, Instant now) {
        // Idempotente: uma issue ABERTA por lojista divergente (unique parcial
        // uq_reconciliation_open) — re-execução não duplica; a original é preservada.
        return jpaRepository.findByMerchantIdAndStatus(merchantId, ReconciliationIssueStatus.OPEN)
                .map(ReconciliationIssueMapper::toDomain)
                .orElseGet(() -> {
                    try {
                        ReconciliationIssue issue = ReconciliationIssue.open(
                                merchantId, expectedBalance, actualBalance, now);
                        return ReconciliationIssueMapper.toDomain(
                                jpaRepository.save(ReconciliationIssueMapper.toEntity(issue)));
                    } catch (DataIntegrityViolationException e) {
                        // Corrida entre jobs/instâncias: o outro venceu — reaproveita.
                        return ReconciliationIssueMapper.toDomain(
                                jpaRepository.findByMerchantIdAndStatus(merchantId, ReconciliationIssueStatus.OPEN)
                                        .orElseThrow());
                    }
                });
    }

    @Override
    public PageResult<ReconciliationIssue> find(UUID merchantId, ReconciliationIssueStatus status,
                                                int page, int size) {
        Page<ReconciliationIssueJpaEntity> result = jpaRepository.findAll(
                spec(merchantId, status),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt")));
        List<ReconciliationIssue> items = result.getContent().stream()
                .map(ReconciliationIssueMapper::toDomain)
                .toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    private Specification<ReconciliationIssueJpaEntity> spec(UUID merchantId,
                                                             ReconciliationIssueStatus status) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (merchantId != null) {
                predicates.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
