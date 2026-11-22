package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.ReceivableJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para receivables — Spec 33.
 */
public interface ReceivableJpaRepository extends JpaRepository<ReceivableJpaEntity, UUID>,
        JpaSpecificationExecutor<ReceivableJpaEntity> {

    Optional<ReceivableJpaEntity> findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
            UUID merchantId, ReceivableOrigin origin, UUID referenceId,
            Collection<ReceivableStatus> statuses);

    List<ReceivableJpaEntity> findByOriginInAndStatusIn(
            Collection<ReceivableOrigin> origins, Collection<ReceivableStatus> statuses);

    boolean existsByMerchantIdAndStatusIn(UUID merchantId, Collection<ReceivableStatus> statuses);
}
