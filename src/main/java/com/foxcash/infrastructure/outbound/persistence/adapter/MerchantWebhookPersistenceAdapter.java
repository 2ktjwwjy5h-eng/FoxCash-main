package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantWebhookRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantWebhookJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantWebhookMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantWebhookJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantWebhookRepository} (Spec 35).
 */
@Repository
public class MerchantWebhookPersistenceAdapter implements MerchantWebhookRepository {

    private static final String ACTIVE = "ACTIVE";

    private final MerchantWebhookJpaRepository jpaRepository;

    public MerchantWebhookPersistenceAdapter(MerchantWebhookJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<MerchantWebhook> findAllActiveByMerchantAndEvent(UUID merchantId, WebhookEventType type) {
        // events e CSV; filtra inscricao no dominio (isSubscribedTo valida ACTIVE + evento).
        return jpaRepository.findByMerchantIdAndStatus(merchantId, ACTIVE).stream()
                .map(MerchantWebhookMapper::toDomain)
                .filter(w -> w.isSubscribedTo(type))
                .toList();
    }

    @Override
    public Optional<MerchantWebhook> findByMerchantIdAndId(UUID merchantId, UUID id) {
        return jpaRepository.findById(id)
                .filter(entity -> entity.getMerchantId().equals(merchantId))
                .map(MerchantWebhookMapper::toDomain);
    }

    @Override
    public List<MerchantWebhook> findAllByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantId(merchantId).stream()
                .map(MerchantWebhookMapper::toDomain)
                .toList();
    }

    @Override
    public MerchantWebhook save(MerchantWebhook webhook) {
        return MerchantWebhookMapper.toDomain(jpaRepository.save(MerchantWebhookMapper.toEntity(webhook)));
    }
}
