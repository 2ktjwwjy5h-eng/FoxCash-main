package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantWebhookJpaEntity;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mapeia {@link MerchantWebhook} (dominio) <-> {@link MerchantWebhookJpaEntity}
 * (JPA). {@code events} e serializado como CSV de nomes de enum.
 */
public final class MerchantWebhookMapper {

    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    private MerchantWebhookMapper() {
    }

    public static MerchantWebhookJpaEntity toEntity(MerchantWebhook webhook) {
        return new MerchantWebhookJpaEntity(
                webhook.id(), webhook.merchantId(), webhook.url(), webhook.secret(),
                toCsv(webhook.events()), webhook.active() ? ACTIVE : INACTIVE,
                webhook.signatureVersion(),
                webhook.createdAt(), webhook.updatedAt());
    }

    public static MerchantWebhook toDomain(MerchantWebhookJpaEntity entity) {
        return new MerchantWebhook(
                entity.getId(), entity.getMerchantId(), entity.getUrl(), entity.getSecret(),
                fromCsv(entity.getEvents()), ACTIVE.equals(entity.getStatus()),
                entity.getSignatureVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static String toCsv(Set<WebhookEventType> events) {
        return events.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<WebhookEventType> fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(WebhookEventType::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
