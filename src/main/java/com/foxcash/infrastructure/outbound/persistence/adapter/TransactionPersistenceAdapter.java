package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.ProviderVolume;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.PixTransactionJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.PixTransactionMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.PixTransactionJpaRepository;
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
 * Adaptador JPA do port TransactionRepository.
 */
@Repository
public class TransactionPersistenceAdapter implements TransactionRepository {

    private final PixTransactionJpaRepository jpaRepository;

    public TransactionPersistenceAdapter(PixTransactionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PixTransaction save(PixTransaction transaction) {
        return PixTransactionMapper.toDomain(jpaRepository.save(PixTransactionMapper.toEntity(transaction)));
    }

    @Override
    public Optional<PixTransaction> findById(UUID transactionId) {
        return jpaRepository.findById(transactionId).map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByIdForUpdate(UUID transactionId) {
        return jpaRepository.findByIdForUpdate(transactionId).map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByExternalReference(String externalReference) {
        return jpaRepository.findByExternalReference(externalReference).map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByExternalReferenceForUpdate(String externalReference) {
        return jpaRepository.findByExternalReferenceForUpdate(externalReference)
                .map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey) {
        return jpaRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findFirstByIdempotencyKeyOrderByCreatedAtDesc(idempotencyKey)
                .map(PixTransactionMapper::toDomain);
    }

    @Override
    public List<PixTransaction> findByMerchantId(UUID merchantId, int limit) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, limit))
                .stream()
                .map(PixTransactionMapper::toDomain)
                .toList();
    }

    @Override
    public List<PixTransaction> findApprovedByMerchantIdBetween(UUID merchantId, Instant from, Instant to) {
        return jpaRepository.findByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, from, to)
                .stream()
                .map(PixTransactionMapper::toDomain)
                .toList();
    }

    @Override
    public long countByMerchantId(UUID merchantId) {
        return jpaRepository.countByMerchantId(merchantId);
    }

    @Override
    public long countByMerchantIdAndStatus(UUID merchantId, TransactionStatus status) {
        return jpaRepository.countByMerchantIdAndStatus(merchantId, status);
    }

    @Override
    public long countPendingWithoutExternalReference() {
        return jpaRepository.countByStatusAndExternalReferenceIsNull(TransactionStatus.PENDING);
    }

    @Override
    public BigDecimal sumAmountByMerchantIdAndStatus(UUID merchantId, TransactionStatus status) {
        return jpaRepository.sumAmountByMerchantIdAndStatus(merchantId, status);
    }

    @Override
    public PageResult<PixTransaction> searchSales(UUID merchantId, UUID profileId,
                                                  TransactionStatus status,
                                                  Instant from, Instant to, int page, int size) {
        Page<PixTransactionJpaEntity> result = jpaRepository.findAll(
                salesSpec(merchantId, profileId, status, from, to),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<PixTransaction> items = result.getContent().stream()
                .map(PixTransactionMapper::toDomain)
                .toList();
        return PageResult.of(items, page, size, result.getTotalElements());
    }

    @Override
    public long countByMerchantIdAndCreatedAtBetween(UUID merchantId, Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndCreatedAtBetween(merchantId, from, to);
    }

    @Override
    public long countByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId,
                                                              TransactionStatus status,
                                                              Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndStatusAndCreatedAtBetween(merchantId, status, from, to);
    }

    @Override
    public BigDecimal sumAmountByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId,
                                                                       TransactionStatus status,
                                                                       Instant from, Instant to) {
        return jpaRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(merchantId, status, from, to);
    }

    // ---- Spec 124: escopo por perfil (filtro rígido do portal) ----

    @Override
    public List<PixTransaction> findByMerchantIdAndProfileId(UUID merchantId, UUID profileId, int limit) {
        return jpaRepository.findByMerchantIdAndProfileIdOrderByCreatedAtDesc(
                        merchantId, profileId, PageRequest.of(0, limit))
                .stream().map(PixTransactionMapper::toDomain).toList();
    }

    @Override
    public List<PixTransaction> findApprovedByMerchantIdBetweenAndProfileId(
            UUID merchantId, UUID profileId, Instant from, Instant to) {
        return jpaRepository.findByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                        merchantId, profileId, TransactionStatus.APPROVED, from, to)
                .stream().map(PixTransactionMapper::toDomain).toList();
    }

    @Override
    public long countByMerchantIdAndProfileIdAndCreatedAtBetween(
            UUID merchantId, UUID profileId, Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndProfileIdAndCreatedAtBetween(
                merchantId, profileId, from, to);
    }

    @Override
    public long countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                merchantId, profileId, status, from, to);
    }

    @Override
    public BigDecimal sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to) {
        return jpaRepository.sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
                merchantId, profileId, status, from, to);
    }

    @Override
    public long countByBotIdInAndStatusAndCreatedAtBetween(List<UUID> botIds,
                                                           TransactionStatus status,
                                                           Instant from, Instant to) {
        if (botIds == null || botIds.isEmpty()) {
            return 0;
        }
        return jpaRepository.countByBotIdInAndStatusAndCreatedAtBetween(botIds, status, from, to);
    }

    @Override
    public BigDecimal sumAmountByBotIdInAndStatusAndCreatedAtBetween(List<UUID> botIds,
                                                                    TransactionStatus status,
                                                                    Instant from, Instant to) {
        if (botIds == null || botIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return jpaRepository.sumAmountByBotIdInAndStatusAndCreatedAtBetween(botIds, status, from, to);
    }

    @Override
    public long countByBotIdAndStatusAndCreatedAtBetween(UUID botId, TransactionStatus status,
                                                         Instant from, Instant to) {
        return jpaRepository.countByBotIdAndStatusAndCreatedAtBetween(botId, status, from, to);
    }

    @Override
    public BigDecimal sumAmountByBotIdAndStatusAndCreatedAtBetween(UUID botId, TransactionStatus status,
                                                                  Instant from, Instant to) {
        return jpaRepository.sumAmountByBotIdAndStatusAndCreatedAtBetween(botId, status, from, to);
    }

    @Override
    public long countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(UUID merchantId,
                                                                              TransactionStatus status,
                                                                              Instant from, Instant to) {
        return jpaRepository.countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                merchantId, status, from, to);
    }

    @Override
    public BigDecimal sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
            UUID merchantId, TransactionStatus status, Instant from, Instant to) {
        return jpaRepository.sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
                merchantId, status, from, to);
    }

    // ---- Spec 107: extrato por provider (visão de gateways do portal) ----

    @Override
    public List<ProviderVolume> sumAndCountApprovedByProvider(UUID merchantId, Instant from, Instant to) {
        return jpaRepository.sumAndCountApprovedByProvider(
                        merchantId, TransactionStatus.APPROVED, from, to)
                .stream()
                .map(row -> new ProviderVolume(
                        (String) row[0],
                        (BigDecimal) row[1],
                        ((Number) row[2]).longValue()))
                .toList();
    }

    /** Filtros dinamicos das vendas do lojista (Spec 25): status/from/to opcionais; profileId (Spec 124) opcional. */
    private Specification<PixTransactionJpaEntity> salesSpec(UUID merchantId, UUID profileId,
                                                             TransactionStatus status,
                                                             Instant from, Instant to) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));
            // Spec 124: header válido → escopa pelo perfil (inválido → vazio, nunca
            // lojista inteiro — o merchant_id acima impede cruzamento de tenant).
            if (profileId != null) {
                predicates.add(cb.equal(root.get("profileId"), profileId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
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
