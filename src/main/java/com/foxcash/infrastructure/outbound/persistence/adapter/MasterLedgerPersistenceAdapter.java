package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterLedgerJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MasterLedgerMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MasterLedgerJpaRepository;
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
 * Adaptador JPA do port {@link MasterLedgerRepository} (Spec 34). Append-only:
 * expoe apenas insert e consultas. Filtros dinamicos via Specifications.
 */
@Repository
public class MasterLedgerPersistenceAdapter implements MasterLedgerRepository {

    private final MasterLedgerJpaRepository jpaRepository;

    public MasterLedgerPersistenceAdapter(MasterLedgerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public MasterLedgerEntry save(MasterLedgerEntry entry) {
        return MasterLedgerMapper.toDomain(jpaRepository.save(MasterLedgerMapper.toEntity(entry)));
    }

    @Override
    public Optional<MasterLedgerEntry> findByReference(UUID merchantId, MasterLedgerEntryType type,
                                                       String referenceType, UUID referenceId) {
        return jpaRepository.findByMerchantIdAndEntryTypeAndReferenceTypeAndReferenceId(
                        merchantId, type, referenceType, referenceId)
                .map(MasterLedgerMapper::toDomain);
    }

    @Override
    public List<MasterLedgerEntry> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId) {
        return jpaRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .stream().map(MasterLedgerMapper::toDomain).toList();
    }

    @Override
    @Transactional
    public MasterLedgerEntry updateAmountAndBalance(UUID entryId, BigDecimal amount, BigDecimal balanceAfter) {
        MasterLedgerJpaEntity entity = jpaRepository.findById(entryId)
                .orElseThrow(() -> new IllegalStateException("Master ledger entry " + entryId + " not found"));
        entity.setAmount(amount);
        entity.setBalanceAfter(balanceAfter);
        return MasterLedgerMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public PageResult<MasterLedgerEntry> findByMerchantId(UUID merchantId,
                                                          MasterLedgerEntryType type,
                                                          Instant from, Instant to,
                                                          int page, int size) {
        Page<MasterLedgerJpaEntity> result = jpaRepository.findAll(
                spec(merchantId, type, from, to),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<MasterLedgerEntry> items = result.getContent().stream()
                .map(MasterLedgerMapper::toDomain)
                .toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    @Override
    public BigDecimal sumAmountByMerchantIdAndTypeBetween(UUID merchantId,
                                                          MasterLedgerEntryType type,
                                                          Instant from, Instant to) {
        return jpaRepository.sumAmountByMerchantIdAndTypeBetween(merchantId, type, from, to);
    }

    @Override
    public BigDecimal sumAmountByTypeBetween(MasterLedgerEntryType type, Instant from, Instant to) {
        return jpaRepository.sumAmountByTypeBetween(type, from, to);
    }

    @Override
    public Optional<MasterLedgerEntry> findLatestByMerchantId(UUID merchantId) {
        return jpaRepository.findFirstByMerchantIdOrderByCreatedAtDesc(merchantId)
                .map(MasterLedgerMapper::toDomain);
    }

    private Specification<MasterLedgerJpaEntity> spec(UUID merchantId,
                                                      MasterLedgerEntryType type,
                                                      Instant from, Instant to) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (merchantId != null) {
                predicates.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("entryType"), type));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
