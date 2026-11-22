package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.WebhookManagementService;
import com.foxcash.domain.exception.InvalidWebhookEventException;
import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookCreateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookCreatedResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookTestRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookTestResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WebhookUpdateRequest;
import com.foxcash.infrastructure.notification.HttpMerchantNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de webhooks do portal (Spec 35): privado por lojista
 * (404 cross-loja via excecao de dominio), segredo devolvido uma vez no 201 e
 * nunca nas demais respostas, evento invalido -> 400 invalid_event, e teste de
 * entrega.
 */
class PortalWebhookControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID WEBHOOK_ID = UUID.randomUUID();

    private final WebhookManagementService webhooks = mock(WebhookManagementService.class);
    private final com.foxcash.domain.port.out.NotificationOutboxPort outbox =
            mock(com.foxcash.domain.port.out.NotificationOutboxPort.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalWebhookController(webhooks, outbox);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private MerchantWebhook webhook() {
        return MerchantWebhook.create(MERCHANT_ID, "https://bot.example.com/hook",
                "segredo-nao-expor", Set.of(WebhookEventType.SALE_PAID));
    }

    @Test
    void listReturnsWebhooksWithoutSecret() {
        when(webhooks.list(MERCHANT_ID)).thenReturn(List.of(webhook()));

        List<WebhookResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        WebhookResponse first = response.get(0);
        assertThat(first.id()).isNotNull();
        assertThat(first.hasSecret()).isTrue();
        assertThat(first.active()).isTrue();
        // O segredo nunca aparece em resposta.
        assertThat(first.toString()).doesNotContain("segredo-nao-expor");
    }

    @Test
    void listIncludesLatestDeliveryErrorFromOutbox() {
        // Spec 119: status/attempts/lastError aditivos (dados reais da Spec 35).
        when(webhooks.list(MERCHANT_ID)).thenReturn(List.of(webhook()));
        when(outbox.findLatestDeliveryError(MERCHANT_ID)).thenReturn(java.util.Optional.of(
                new com.foxcash.domain.model.notification.OutboxNotification(
                        UUID.randomUUID(), MERCHANT_ID, "SALE_PAID", "{}",
                        java.time.Instant.now(), 3, "HTTP 500", null)));

        List<WebhookResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).status()).isEqualTo("ACTIVE");
        assertThat(response.get(0).attempts()).isEqualTo(3);
        assertThat(response.get(0).lastError()).isEqualTo("HTTP 500");
    }

    @Test
    void sampleRequiresOwnedWebhook() {
        // Spec 119: /{id}/sample é privado por lojista (404 cross-loja).
        when(webhooks.list(MERCHANT_ID)).thenReturn(List.of(webhook()));

        assertThatThrownBy(() -> controller.sample(authentication, UUID.randomUUID(), "SALE_PAID"))
                .isInstanceOf(com.foxcash.domain.exception.WebhookNotFoundException.class);
    }

    @Test
    void createReturns201WithSecretOnce() {
        when(webhooks.create(eq(MERCHANT_ID), any(), any()))
                .thenReturn(new WebhookManagementService.CreatedWebhook(webhook(), "segredo-unico"));

        ResponseEntity<WebhookCreatedResponse> response = controller.create(authentication,
                new WebhookCreateRequest("https://bot.example.com/hook", List.of("SALE_PAID")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().secret()).isEqualTo("segredo-unico");
        assertThat(response.getBody().url()).isEqualTo("https://bot.example.com/hook");
    }

    @Test
    void updatePropagatesOwnership() {
        when(webhooks.update(eq(MERCHANT_ID), eq(WEBHOOK_ID), any(), any(), any())).thenReturn(webhook());

        controller.update(authentication, WEBHOOK_ID,
                new WebhookUpdateRequest("https://novo.example.com/hook", null, null));

        verify(webhooks).update(MERCHANT_ID, WEBHOOK_ID, "https://novo.example.com/hook", null, null);
    }

    @Test
    void activateAndDeactivateReturn204() {
        ResponseEntity<Void> activate = controller.activate(authentication, WEBHOOK_ID);
        ResponseEntity<Void> deactivate = controller.deactivate(authentication, WEBHOOK_ID);

        assertThat(activate.getStatusCode().value()).isEqualTo(204);
        assertThat(deactivate.getStatusCode().value()).isEqualTo(204);
        verify(webhooks).setActive(MERCHANT_ID, WEBHOOK_ID, true);
        verify(webhooks).setActive(MERCHANT_ID, WEBHOOK_ID, false);
    }

    @Test
    void invalidEventPropagatesInvalidWebhookEvent() {
        when(webhooks.create(eq(MERCHANT_ID), any(), any()))
                .thenThrow(new InvalidWebhookEventException("hacker"));

        assertThatThrownBy(() -> controller.create(authentication,
                new WebhookCreateRequest("https://x.example.com", List.of("hacker"))))
                .isInstanceOf(InvalidWebhookEventException.class);
    }

    @Test
    void testReturnsDeliveryResultForOwnedUrl() {
        // Auditoria 2026-08-05 (B-05): o teste é por conta do lojista — o
        // merchantId do principal é repassado ao serviço (que exige URL própria).
        when(webhooks.test(eq(MERCHANT_ID), eq("https://x.example.com"), eq("seg"), eq("SALE_PAID")))
                .thenReturn(HttpMerchantNotifier.DeliveryResult.ok(200));

        WebhookTestResponse response = controller.test(authentication,
                new WebhookTestRequest("https://x.example.com", "seg", "SALE_PAID"));

        assertThat(response.delivered()).isTrue();
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
