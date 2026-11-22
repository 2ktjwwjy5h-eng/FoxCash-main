package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreBotJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link StoreBot} e a entidade JPA
 * {@link StoreBotJpaEntity} (Spec 74). O token é criptografado em repouso pela
 * {@code CryptoConverter} (o mapper passa o valor em claro para a entidade);
 * o {@code webhook_secret} em claro **nunca** é persistido — só o hash.
 */
public final class StoreBotMapper {

    private StoreBotMapper() {
    }

    public static StoreBotJpaEntity toEntity(StoreBot bot) {
        return new StoreBotJpaEntity(
                bot.id(),
                bot.merchantId(),
                bot.profileId(),
                bot.platform(),
                bot.token(),
                bot.tokenHash(),
                bot.webhookSecretHash(),
                bot.webhookSecretTokenHash(),
                bot.displayName(),
                bot.username(),
                bot.applicationId(),
                bot.status(),
                bot.errorMessage(),
                bot.lastHealthCheckAt(),
                bot.deletedAt(),
                bot.createdAt(),
                bot.updatedAt());
    }

    public static StoreBot toDomain(StoreBotJpaEntity entity) {
        // O webhook secret em claro não é persistido — o domínio fica com null e
        // a integração gera um novo quando precisar (setWebhook/reconexão).
        // O mesmo vale para o secret token do header (auditoria 2026-08-18) —
        // só o hash é persistido.
        StoreBot bot = StoreBot.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getPlatform(),
                entity.getToken(),
                entity.getTokenHash(),
                entity.getWebhookSecretHash(),
                entity.getWebhookSecretTokenHash(),
                entity.getDisplayName(),
                entity.getUsername(),
                entity.getApplicationId(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getLastHealthCheckAt(),
                entity.getDeletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
        // Perfil de operação (Spec 122) — atribuído após o restore (campo novo).
        bot.assignProfile(entity.getProfileId());
        return bot;
    }
}
