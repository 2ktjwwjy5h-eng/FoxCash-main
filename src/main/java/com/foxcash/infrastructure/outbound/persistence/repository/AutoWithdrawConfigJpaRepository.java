package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.AutoWithdrawConfigJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) da config de saque automático
 * (Spec 70).
 */
public interface AutoWithdrawConfigJpaRepository
        extends JpaRepository<AutoWithdrawConfigJpaEntity, UUID> {

    Optional<AutoWithdrawConfigJpaEntity> findByMerchantId(UUID merchantId);

    /** Configs habilitadas — o job itera sobre elas a cada execução. */
    List<AutoWithdrawConfigJpaEntity> findByEnabledTrue();
}
