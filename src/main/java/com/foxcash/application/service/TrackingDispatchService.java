package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gatilhos de tracking do funil da Spec 75 (Spec 81/D5.1) — chamados
 * na MESMA transação do evento do funil. Para cada perfil ACTIVE vinculado ao
 * bot, publica **um job de outbox por (perfil, evento)** (Spec 35) — o envio
 * aos provedores acontece depois, no {@link TrackingDispatchHandler}.
 */
@Service
public class TrackingDispatchService {

    private final TrackingProfileRepository profileRepository;
    private final WebhookEventPublisher eventPublisher;
    private final TrackingProperties properties;

    public TrackingDispatchService(TrackingProfileRepository profileRepository,
                                   WebhookEventPublisher eventPublisher,
                                   TrackingProperties properties) {
        this.profileRepository = profileRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** Novo lead (Spec 75) — {@code lead} → Lead/PageView nos provedores. */
    @Transactional
    public void onLead(UUID botId, String platformUserId, UtmParams utm) {
        if (!properties.isDispatchEnabled() || botId == null) {
            return;
        }
        dispatch(botId, "LEAD", leadEventId(botId, platformUserId),
                baseData(platformUserId, null, utm));
    }

    /** {@code checkout_started} — InitiateCheckout (value = valor da cobrança). */
    @Transactional
    public void onCheckout(UUID botId, String platformUserId, UUID transactionId, Money amount) {
        if (!properties.isDispatchEnabled() || botId == null || transactionId == null) {
            return;
        }
        dispatch(botId, "INITIATE_CHECKOUT", checkoutEventId(botId, transactionId),
                baseData(platformUserId, amount, null));
    }

    /** {@code paid} — Purchase (value = valor pago, currency BRL). */
    @Transactional
    public void onPurchase(UUID botId, String platformUserId, UUID transactionId, Money amount) {
        if (!properties.isDispatchEnabled() || botId == null || transactionId == null) {
            return;
        }
        dispatch(botId, "PURCHASE", purchaseEventId(botId, transactionId),
                baseData(platformUserId, amount, null));
    }

    /** ID estável do evento "lead" (Spec 81) — o funil não expõe o id do lead. */
    public static UUID leadEventId(UUID botId, String platformUserId) {
        return UUID.nameUUIDFromBytes(("lead:" + botId + ":" + platformUserId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * IDs estáveis e **distintos por tipo** (achado do E2E — audit pós 81): o
     * checkout e o paid da MESMA transação NÃO podem compartilhar o
     * {@code transaction_id} como event_id — o {@code event_logs} tem UNIQUE
     * {@code (profile_id, event_id)} e o upsert sobrescreveria um pelo outro.
     * Prefixo por tipo garante unicidade + estabilidade (replay não duplica).
     */
    public static UUID checkoutEventId(UUID botId, UUID transactionId) {
        return UUID.nameUUIDFromBytes(("checkout:" + botId + ":" + transactionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static UUID purchaseEventId(UUID botId, UUID transactionId) {
        return UUID.nameUUIDFromBytes(("paid:" + botId + ":" + transactionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void dispatch(UUID botId, String eventType, UUID eventId, Map<String, Object> base) {
        List<TrackingProfile> profiles = profileRepository.findActiveByBotId(botId);
        if (profiles.isEmpty()) {
            return; // bot sem perfil ACTIVE → nada a disparar (critério 12)
        }
        for (TrackingProfile profile : profiles) {
            Map<String, Object> data = new LinkedHashMap<>(base);
            data.put("profileId", profile.id().toString());
            data.put("eventType", eventType);
            data.put("eventId", eventId.toString());
            data.put("botId", botId.toString());
            eventPublisher.publish(profile.merchantId(), WebhookEventType.TRACKING_DISPATCH, data);
        }
    }

    private Map<String, Object> baseData(String platformUserId, Money amount, UtmParams utm) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("platformUserId", platformUserId);
        if (amount != null) {
            data.put("value", amount.amount().toPlainString());
        }
        data.put("currency", "BRL");
        if (utm != null && utm.hasAny()) {
            data.put("utmSource", utm.source());
            data.put("utmMedium", utm.medium());
            data.put("utmCampaign", utm.campaign());
            data.put("utmContent", utm.content());
            data.put("utmTerm", utm.term());
        }
        return data;
    }
}
