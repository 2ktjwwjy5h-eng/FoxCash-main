package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.StoreBotMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantProfileJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.StoreBotJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link StoreBotRepository} (Spec 74).
 */
@Repository
public class StoreBotPersistenceAdapter implements StoreBotRepository {

    private final StoreBotJpaRepository jpaRepository;
    private final MerchantProfileJpaRepository profileJpaRepository;

    public StoreBotPersistenceAdapter(StoreBotJpaRepository jpaRepository,
                                      MerchantProfileJpaRepository profileJpaRepository) {
        this.jpaRepository = jpaRepository;
        this.profileJpaRepository = profileJpaRepository;
    }

    @Override
    public StoreBot save(StoreBot bot) {
        return StoreBotMapper.toDomain(jpaRepository.save(StoreBotMapper.toEntity(bot)));
    }

    @Override
    public Optional<StoreBot> findById(UUID id) {
        return jpaRepository.findById(id).map(StoreBotMapper::toDomain);
    }

    @Override
    public Optional<StoreBot> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId).map(StoreBotMapper::toDomain);
    }

    @Override
    public List<StoreBot> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(merchantId)
                .stream().map(StoreBotMapper::toDomain).toList();
    }

    @Override
    public List<StoreBot> findByMerchantIdAndProfileId(UUID merchantId, UUID profileId) {
        return jpaRepository.findFleetByMerchantIdAndProfile(
                        merchantId, profileId, includeUnassigned(merchantId, profileId))
                .stream().map(StoreBotMapper::toDomain).toList();
    }

    @Override
    public List<UUID> findIdsByMerchantIdAndProfileId(UUID merchantId, UUID profileId) {
        return jpaRepository.findIdsByMerchantIdAndProfile(
                merchantId, profileId, includeUnassigned(merchantId, profileId));
    }

    /**
     * Spec 122/124: bots com {@code profile_id} NULL (legado ou registro novo —
     * o registro não atribui perfil) pertencem **logicamente** ao perfil DEFAULT
     * do lojista. Quando o perfil ativo é o default, a frota/bot-set inclui os
     * bots sem perfil (achado de auditoria 2026-08-15 — bots-panel/CRM/
     * broadcasts/tracking vazios com o default ativo no header).
     */
    private boolean includeUnassigned(UUID merchantId, UUID profileId) {
        if (profileId == null) {
            return false;
        }
        return profileJpaRepository.findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(merchantId)
                .map(p -> p.getId().equals(profileId))
                .orElse(false);
    }

    @Override
    public Optional<StoreBot> findByWebhookSecretHash(String webhookSecretHash) {
        return jpaRepository.findByWebhookSecretHash(webhookSecretHash).map(StoreBotMapper::toDomain);
    }

    @Override
    public Optional<StoreBot> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(StoreBotMapper::toDomain);
    }

    @Override
    public List<StoreBot> findAllNonDeleted() {
        return jpaRepository.findByDeletedAtIsNullOrderByCreatedAtAsc()
                .stream().map(StoreBotMapper::toDomain).toList();
    }

    @Override
    public List<StoreBot> findByStatus(BotStatus status) {
        return jpaRepository.findByStatusAndDeletedAtIsNull(status)
                .stream().map(StoreBotMapper::toDomain).toList();
    }
}
