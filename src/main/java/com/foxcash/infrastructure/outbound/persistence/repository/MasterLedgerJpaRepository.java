package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterLedgerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) do ledger do master — Spec 34.
 */
public interface MasterLedgerJpaRepository
        extends JpaRepository<MasterLedgerJpaEntity, UUID>,
        JpaSpecificationExecutor<MasterLedgerJpaEntity> {

    /** Entry unica por (lojista, tipo, origem) — usada na recovery cumulativa. */
    Optional<MasterLedgerJpaEntity> findByMerchantIdAndEntryTypeAndReferenceTypeAndReferenceId(
            UUID merchantId, MasterLedgerEntryType entryType, String referenceType, UUID referenceId);

    /** Última movimentacao do lojista (saldo de referencia para balance_after). */
    Optional<MasterLedgerJpaEntity> findFirstByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /** Todas as entries de uma origem (Spec 125 — trace). */
    List<MasterLedgerJpaEntity> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /** Soma das entries de um lojista por tipo no período (summary da Spec 34). */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM MasterLedgerJpaEntity e "
            + "WHERE e.merchantId = :merchantId AND e.entryType = :type "
            + "AND e.createdAt >= :from AND e.createdAt <= :to")
    BigDecimal sumAmountByMerchantIdAndTypeBetween(@Param("merchantId") UUID merchantId,
                                                   @Param("type") MasterLedgerEntryType type,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to);

    /** Soma global (todos os lojistas) por tipo no período — summary da Spec 34. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM MasterLedgerJpaEntity e "
            + "WHERE e.entryType = :type "
            + "AND e.createdAt >= :from AND e.createdAt <= :to")
    BigDecimal sumAmountByTypeBetween(@Param("type") MasterLedgerEntryType type,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to);
}
