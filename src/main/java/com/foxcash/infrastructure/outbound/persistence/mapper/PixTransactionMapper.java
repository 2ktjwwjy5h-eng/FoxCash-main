package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.infrastructure.outbound.persistence.entity.PixTransactionJpaEntity;

/**
 * Mapeia entre o modelo de dominio PixTransaction e a entidade JPA
 * PixTransactionJpaEntity.
 */
public final class PixTransactionMapper {

    private PixTransactionMapper() {
    }

    public static PixTransactionJpaEntity toEntity(PixTransaction transaction) {
        PixTransactionJpaEntity entity = new PixTransactionJpaEntity(
                transaction.id(),
                transaction.merchantId(),
                transaction.amount().amount(),
                transaction.description(),
                transaction.pixKey(),
                transaction.endToEndId(),
                transaction.externalReference(),
                transaction.buyerIdHash(),
                transaction.pixQrCode(),
                transaction.pixCopyPaste(),
                transaction.status(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.paidAt(),
                transaction.idempotencyKey(),
                transaction.planId(),
                transaction.botId(),
                transaction.platform(),
                transaction.platformUserId(),
                transaction.termsAcceptedAt(),
                transaction.buyerIp(),
                transaction.buyerDevice());
        // Spec 91 (V58): adquirente que criou a cobrança (null sem router).
        entity.setProvider(transaction.provider());
        // Spec 124 (V70): perfil de operação da transação (null = legado).
        entity.setProfileId(transaction.profileId());
        // Spec 127 (V74): traceId de negócio = id da cobrança (correlação de logs).
        entity.setTraceId(transaction.traceId());
        return entity;
    }

    public static PixTransaction toDomain(PixTransactionJpaEntity entity) {
        PixTransaction transaction = PixTransaction.restore(
                entity.getId(),
                entity.getMerchantId(),
                Money.of(entity.getAmount()),
                entity.getDescription(),
                entity.getPixKey(),
                entity.getEndToEndId(),
                entity.getExternalReference(),
                entity.getPixQrCode(),
                entity.getPixCopyPaste(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPaidAt(),
                entity.getBuyerIdHash(),
                entity.getIdempotencyKey(),
                entity.getPlanId(),
                entity.getBotId(),
                entity.getPlatform(),
                entity.getPlatformUserId(),
                entity.getTermsAcceptedAt(),
                entity.getBuyerIp(),
                entity.getBuyerDevice());
        // Spec 91 (V58): restaura o adquirente (SEM touch — metadado de origem).
        if (entity.getProvider() != null) {
            transaction.attachProvider(entity.getProvider());
        }
        // Spec 124 (V70): restaura o perfil de operação (SEM touch — metadado).
        if (entity.getProfileId() != null) {
            transaction.attachProfile(entity.getProfileId());
        }
        return transaction;
    }
}
