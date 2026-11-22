package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantWebhookJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantWebhookJpaRepository extends JpaRepository<MerchantWebhookJpaEntity, UUID> {

    List<MerchantWebhookJpaEntity> findByMerchantId(UUID merchantId);

    List<MerchantWebhookJpaEntity> findByMerchantIdAndStatus(UUID merchantId, String status);
}
