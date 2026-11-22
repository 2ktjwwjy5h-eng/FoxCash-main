package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.infrastructure.outbound.persistence.entity.WalletLedgerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) do ledger do wallet — Spec 27.
 */
public interface WalletLedgerJpaRepository
        extends JpaRepository<WalletLedgerJpaEntity, UUID>,
        JpaSpecificationExecutor<WalletLedgerJpaEntity> {

    boolean existsByMerchantId(UUID merchantId);

    /** Entry unica por (merchant, tipo, origem) — usada na cobranca cumulativa (Spec 33). */
    Optional<WalletLedgerJpaEntity> findByMerchantIdAndEntryTypeAndReferenceTypeAndReferenceId(
            UUID merchantId, WalletLedgerEntryType entryType, String referenceType, UUID referenceId);

    /** Última movimentação do lojista (referência para a Spec 30). */
    Optional<WalletLedgerJpaEntity> findFirstByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    /** Todas as entries de uma origem (Spec 125 — trace). */
    List<WalletLedgerJpaEntity> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /** Soma das movimentações do lojista (backstop da Spec 30). */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM WalletLedgerJpaEntity e "
            + "WHERE e.merchantId = :merchantId")
    BigDecimal sumAmountByMerchantId(@Param("merchantId") UUID merchantId);
}
