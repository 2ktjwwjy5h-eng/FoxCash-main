package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.WalletLedgerJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.WalletLedgerMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.WalletLedgerJpaRepository;
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
 * Adaptador JPA do port {@link WalletLedgerRepository} (Spec 27). Append-only:
 * expõe apenas insert e consultas. Filtros dinâmicos via Specifications.
 */
@Repository
public class WalletLedgerPersistenceAdapter implements WalletLedgerRepository {

    private final WalletLedgerJpaRepository jpaRepository;

    public WalletLedgerPersistenceAdapter(WalletLedgerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public WalletLedgerEntry save(WalletLedgerEntry entry) {
        return WalletLedgerMapper.toDomain(jpaRepository.save(WalletLedgerMapper.toEntity(entry)));
    }

    @Override
    public boolean existsByMerchantId(UUID merchantId) {
        return jpaRepository.existsByMerchantId(merchantId);
    }

    @Override
    public Optional<WalletLedgerEntry> findByReference(UUID merchantId, WalletLedgerEntryType type,
                                                       String referenceType, UUID referenceId) {
        return jpaRepository.findByMerchantIdAndEntryTypeAndReferenceTypeAndReferenceId(
                        merchantId, type, referenceType, referenceId)
                .map(WalletLedgerMapper::toDomain);
    }

    @Override
    public List<WalletLedgerEntry> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId) {
        return jpaRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .stream().map(WalletLedgerMapper::toDomain).toList();
    }

    @Override
    @Transactional
    public WalletLedgerEntry updateAmountAndBalance(UUID entryId, BigDecimal amount, BigDecimal balanceAfter) {
        WalletLedgerJpaEntity entity = jpaRepository.findById(entryId)
                .orElseThrow(() -> new IllegalStateException("Ledger entry " + entryId + " not found"));
        entity.setAmount(amount);
        entity.setBalanceAfter(balanceAfter);
        return WalletLedgerMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public PageResult<WalletLedgerEntry> findByMerchantId(UUID merchantId,
                                                          WalletLedgerEntryType type,
                                                          Instant from, Instant to,
                                                          int page, int size) {
        Page<WalletLedgerJpaEntity> result = jpaRepository.findAll(
                spec(merchantId, type, from, to),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<WalletLedgerEntry> items = result.getContent().stream()
                .map(WalletLedgerMapper::toDomain)
                .toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    @Override
    public BigDecimal sumAmountByMerchantId(UUID merchantId) {
        return jpaRepository.sumAmountByMerchantId(merchantId);
    }

    @Override
    public Optional<WalletLedgerEntry> findLatestByMerchantId(UUID merchantId) {
        return jpaRepository.findFirstByMerchantIdOrderByCreatedAtDesc(merchantId)
                .map(WalletLedgerMapper::toDomain);
    }

    private Specification<WalletLedgerJpaEntity> spec(UUID merchantId,
                                                      WalletLedgerEntryType type,
                                                      Instant from, Instant to) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));
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
