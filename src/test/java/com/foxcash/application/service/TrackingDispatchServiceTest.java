package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingStatus;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Testes do {@link TrackingDispatchService} (Spec 81/D5.1). */
class TrackingDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final TrackingProfileRepository profileRepository = mock(TrackingProfileRepository.class);
    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private final TrackingProperties properties = new TrackingProperties();
    private final TrackingDispatchService service =
            new TrackingDispatchService(profileRepository, eventPublisher, properties);

    private TrackingProfile profile(UUID id, UUID merchantId) {
        return TrackingProfile.restore(id, merchantId, "Pixel Modelo", TrackingStatus.ACTIVE,
                "PIXEL", "tok", null, null, null, null, NOW, NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onLeadPublishesOneOutboxJobPerActiveProfile() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(profileRepository.findActiveByBotId(BOT_ID)).thenReturn(
                List.of(profile(p1, UUID.randomUUID()), profile(p2, UUID.randomUUID())));

        service.onLead(BOT_ID, "u-1", UtmParams.of("fb", null, "promo", null, null));

        ArgumentCaptor<UUID> merchantCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<WebhookEventType> typeCaptor = ArgumentCaptor.forClass(WebhookEventType.class);
        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publish(merchantCaptor.capture(), typeCaptor.capture(),
                dataCaptor.capture());
        assertThat(typeCaptor.getAllValues()).containsOnly(WebhookEventType.TRACKING_DISPATCH);
        for (Object data : dataCaptor.getAllValues()) {
            Map<String, Object> map = (Map<String, Object>) data;
            assertThat(map).containsEntry("eventType", "LEAD").containsEntry("botId", BOT_ID.toString());
            assertThat(map).containsEntry("utmSource", "fb").containsEntry("utmCampaign", "promo");
            assertThat(map).doesNotContainKey("value");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void onCheckoutAndOnPurchaseCarryValueAndCurrency() {
        when(profileRepository.findActiveByBotId(BOT_ID)).thenReturn(List.of(profile(UUID.randomUUID(), UUID.randomUUID())));

        service.onCheckout(BOT_ID, "u-1", TX_ID, Money.of("49.90"));
        service.onPurchase(BOT_ID, "u-1", TX_ID, Money.of("49.90"));

        ArgumentCaptor<Object> dataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publish(any(), any(), dataCaptor.capture());
        Map<String, Object> checkout = (Map<String, Object>) dataCaptor.getAllValues().get(0);
        Map<String, Object> purchase = (Map<String, Object>) dataCaptor.getAllValues().get(1);
        assertThat(checkout).containsEntry("eventType", "INITIATE_CHECKOUT")
                .containsEntry("value", "49.90").containsEntry("currency", "BRL");
        assertThat(purchase).containsEntry("eventType", "PURCHASE")
                .containsEntry("value", "49.90").containsEntry("currency", "BRL");
        // Achado do E2E (audit pós 81): checkout e paid da MESMA transação NÃO
        // podem compartilhar o event_id (UNIQUE (profile_id, event_id) — o
        // upsert sobrescreveria um pelo outro). IDs derivados por tipo.
        assertThat(checkout.get("eventId")).isNotEqualTo(purchase.get("eventId"));
        assertThat(checkout.get("eventId")).isEqualTo(
                TrackingDispatchService.checkoutEventId(BOT_ID, TX_ID).toString());
        assertThat(purchase.get("eventId")).isEqualTo(
                TrackingDispatchService.purchaseEventId(BOT_ID, TX_ID).toString());
    }

    @Test
    void noProfilesOrDispatchDisabledMeansNoPublish() {
        when(profileRepository.findActiveByBotId(BOT_ID)).thenReturn(List.of());

        service.onLead(BOT_ID, "u-1", null);
        verify(eventPublisher, never()).publish(any(), any(), any());

        properties.setDispatchEnabled(false);
        when(profileRepository.findActiveByBotId(BOT_ID))
                .thenReturn(List.of(profile(UUID.randomUUID(), UUID.randomUUID())));
        service.onPurchase(BOT_ID, "u-1", TX_ID, Money.of("10.00"));
        verify(eventPublisher, never()).publish(any(), any(), any());
    }
}
