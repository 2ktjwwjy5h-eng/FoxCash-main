package com.foxcash.domain.model.notification;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link MerchantWebhook} (Spec 35): imutabilidade nas operações de
 * atualização/ativação e regra de assinatura (ativo E inscrito no evento).
 */
class MerchantWebhookTest {

    @Test
    void createBuildsActiveWebhookWithEvents() {
        MerchantWebhook webhook = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/x", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED, WebhookEventType.SALE_PAID));

        assertThat(webhook.id()).isNotNull();
        assertThat(webhook.active()).isTrue();
        assertThat(webhook.events()).containsExactlyInAnyOrder(
                WebhookEventType.CHARGE_CREATED, WebhookEventType.SALE_PAID);
        assertThat(webhook.createdAt()).isEqualTo(webhook.updatedAt());
    }

    @Test
    void withUrlReplacesUrlAndBumpsUpdatedAt() {
        MerchantWebhook original = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/old", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED));

        MerchantWebhook updated = original.withUrl("https://hooks.example.com/new");

        assertThat(updated.url()).isEqualTo("https://hooks.example.com/new");
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.events()).isEqualTo(original.events());
        assertThat(updated.active()).isTrue();
        assertThat(updated.updatedAt()).isAfterOrEqualTo(original.updatedAt());
    }

    @Test
    void withEventsReplacesSubscribedEvents() {
        MerchantWebhook original = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/x", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED));

        MerchantWebhook updated = original.withEvents(Set.of(WebhookEventType.SALE_REFUNDED));

        assertThat(updated.events()).containsExactly(WebhookEventType.SALE_REFUNDED);
        assertThat(updated.url()).isEqualTo(original.url());
        assertThat(updated.active()).isTrue();
    }

    @Test
    void deactivateFlipsActiveFlag() {
        MerchantWebhook webhook = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/x", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED));

        MerchantWebhook deactivated = webhook.deactivate();

        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.id()).isEqualTo(webhook.id());
        assertThat(deactivated.events()).isEqualTo(webhook.events());
    }

    @Test
    void activateTurnsInactiveWebhookActiveAgain() {
        MerchantWebhook inactive = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/x", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED)).deactivate();

        assertThat(inactive.active()).isFalse();
        assertThat(inactive.activate().active()).isTrue();
    }

    @Test
    void isSubscribedToRequiresActiveAndEventSubscription() {
        MerchantWebhook active = MerchantWebhook.create(
                java.util.UUID.randomUUID(), "https://hooks.example.com/x", "segredo",
                Set.of(WebhookEventType.CHARGE_CREATED));
        MerchantWebhook inactive = active.deactivate();

        assertThat(active.isSubscribedTo(WebhookEventType.CHARGE_CREATED)).isTrue();
        assertThat(active.isSubscribedTo(WebhookEventType.SALE_PAID)).isFalse();
        assertThat(inactive.isSubscribedTo(WebhookEventType.CHARGE_CREATED)).isFalse();
        assertThat(inactive.isSubscribedTo(WebhookEventType.SALE_PAID)).isFalse();
    }
}
