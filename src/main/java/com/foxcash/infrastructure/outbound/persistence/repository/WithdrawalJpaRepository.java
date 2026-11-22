package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.infrastructure.outbound.persistence.entity.WithdrawalJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para saidas (saques) — Spec 14,
 * evoluido pela Spec 36 (external_reference + reconciliacao de PROCESSING).
 */
public interface WithdrawalJpaRepository extends JpaRepository<WithdrawalJpaEntity, UUID>,
        JpaSpecificationExecutor<WithdrawalJpaEntity> {

    /** Lista do lojista limitada (auditoria 2026-08-05: nunca carrega tudo). */
    List<WithdrawalJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    /** Soma das saidas COMPLETED do lojista (ganhos do dashboard master, Spec 15). */
    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WithdrawalJpaEntity w "
            + "WHERE w.merchantId = :merchantId AND w.status = com.foxcash.domain.model.withdrawal.WithdrawalStatus.COMPLETED")
    BigDecimal sumCompletedAmountByMerchantId(@Param("merchantId") UUID merchantId);

    /** Soma das saidas COMPLETED do lojista dentro do intervalo (fluxo de caixa do portal, Spec 22). */
    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WithdrawalJpaEntity w "
            + "WHERE w.merchantId = :merchantId AND w.status = com.foxcash.domain.model.withdrawal.WithdrawalStatus.COMPLETED "
            + "AND w.createdAt >= :from AND w.createdAt < :to")
    BigDecimal sumCompletedAmountByMerchantIdBetween(@Param("merchantId") UUID merchantId,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);

    /**
     * Soma de {@code amount + fee} das saídas do tipo criadas desde {@code since}
     * (Spec 120 — teto diário do PVS: TODAS as tentativas do dia contabilizam,
     * qualquer status). Usa o índice {@code ix_withdrawals_merchant_type_created} (V71).
     */
    @Query("SELECT COALESCE(SUM(w.amount), 0) + COALESCE(SUM(w.feeAmount), 0) FROM WithdrawalJpaEntity w "
            + "WHERE w.merchantId = :merchantId AND w.type = :type AND w.createdAt >= :since")
    BigDecimal sumAmountAndFeeByMerchantAndTypeCreatedSince(@Param("merchantId") UUID merchantId,
                                                            @Param("type") WithdrawalType type,
                                                            @Param("since") Instant since);

    /** Webhook de saida (Spec 36): lock pessimista no saque por referencia externa. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WithdrawalJpaEntity w WHERE w.externalReference = :externalReference")
    Optional<WithdrawalJpaEntity> findByExternalReferenceForUpdate(@Param("externalReference") String externalReference);

    /** SEC-22: lock pessimista no saque por id (estorno de falha síncrona — serializa com webhook/reconciliação). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WithdrawalJpaEntity w WHERE w.id = :id")
    Optional<WithdrawalJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Reconciliacao (Spec 36): PROCESSING orfaos mais antigos que o limite. */
    @Query("SELECT w FROM WithdrawalJpaEntity w "
            + "WHERE w.status = com.foxcash.domain.model.withdrawal.WithdrawalStatus.PROCESSING "
            + "AND w.createdAt < :cutoff")
    List<WithdrawalJpaEntity> findProcessingOlderThan(@Param("cutoff") Instant cutoff);
}
