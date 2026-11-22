package com.foxcash.application.service;

import com.foxcash.domain.exception.WebhookNotFoundException;
import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantWebhookRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import com.foxcash.infrastructure.notification.HttpMerchantNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da gestao de webhooks de saida (Spec 35) — SSRF (auditoria 2026-08-05):
 * create/update rejeitam URL interna com 400 ({@code IllegalArgumentException}),
 * aceitam URL publica e o segredo e devolvido uma unica vez.
 */
class WebhookManagementServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MerchantWebhookRepository webhookRepository = mock(MerchantWebhookRepository.class);
    private final HttpMerchantNotifier httpNotifier = mock(HttpMerchantNotifier.class);
    private NotificationProperties properties;
    private WebhookManagementService service;

    @BeforeEach
    void setUp() {
        properties = new NotificationProperties(); // allowLoopback = false (seguro)
        service = new WebhookManagementService(webhookRepository, httpNotifier, properties);
        when(webhookRepository.save(any(MerchantWebhook.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createAcceptsPublicUrlAndReturnsSecretOnce() {
        WebhookManagementService.CreatedWebhook created =
                service.create(MERCHANT_ID, "https://8.8.8.8/hook", List.of("SALE_PAID"));

        assertThat(created.secret()).matches("[0-9a-f]{64}");
        assertThat(created.webhook().url()).isEqualTo("https://8.8.8.8/hook");
        assertThat(created.webhook().events()).containsExactly(WebhookEventType.SALE_PAID);
        verify(webhookRepository).save(any(MerchantWebhook.class));
    }

    @Test
    void createRejectsLoopbackUrl() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, "http://127.0.0.1:8080/hook", List.of("SALE_PAID")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(webhookRepository, never()).save(any());
    }

    @Test
    void createRejectsMetadataCloudUrl() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, "http://169.254.169.254/latest/meta-data/", List.of("SALE_PAID")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(webhookRepository, never()).save(any());
    }

    @Test
    void createRejectsNonHttpScheme() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, "ftp://8.8.8.8/hook", List.of("SALE_PAID")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(webhookRepository, never()).save(any());
    }

    @Test
    void updateRejectsInternalUrlButAcceptsPublic() {
        MerchantWebhook existing = MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                "segredo", java.util.Set.of(WebhookEventType.SALE_PAID));
        when(webhookRepository.findByMerchantIdAndId(MERCHANT_ID, existing.id())).thenReturn(java.util.Optional.of(existing));
        when(webhookRepository.save(any(MerchantWebhook.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.update(MERCHANT_ID, existing.id(), "http://192.168.1.1/hook", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(webhookRepository, never()).save(any());

        MerchantWebhook updated = service.update(MERCHANT_ID, existing.id(), "https://8.8.8.8/novo", null, null);
        assertThat(updated.url()).isEqualTo("https://8.8.8.8/novo");
    }

    @Test
    void updateRejectsInvalidSignatureVersionButAcceptsV1() {
        // Spec 148 (achado da auditoria 2026-08-21): a whitelist aceita SOMENTE
        // v1/v2 — um valor inválido persistido viraria v2 silenciosamente no
        // notifier (isV2Signature = qualquer coisa != v1).
        MerchantWebhook existing = MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                "segredo", java.util.Set.of(WebhookEventType.SALE_PAID));
        when(webhookRepository.findByMerchantIdAndId(MERCHANT_ID, existing.id()))
                .thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> service.update(MERCHANT_ID, existing.id(), null, null, "v3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signatureVersion");
        verify(webhookRepository, never()).save(any());

        MerchantWebhook downgraded = service.update(MERCHANT_ID, existing.id(), null, null, "v1");
        assertThat(downgraded.signatureVersion()).isEqualTo("v1");
        assertThat(downgraded.isV2Signature()).isFalse();
    }

    @Test
    void allowsLoopbackWhenExplicitlyEnabled() {
        properties.setAllowLoopback(true);

        WebhookManagementService.CreatedWebhook created =
                service.create(MERCHANT_ID, "http://127.0.0.1:8080/hook", List.of("SALE_PAID"));

        assertThat(created.webhook().url()).isEqualTo("http://127.0.0.1:8080/hook");
        verify(webhookRepository).save(any(MerchantWebhook.class));
    }

    // ---------- Auditoria 2026-08-05 (B-05): teste de entrega restrito às
    // URLs cadastradas do próprio lojista ----------

    @Test
    void testAcceptsOwnedUrlAndPosts() {
        String owned = "https://8.8.8.8/hook";
        when(webhookRepository.findAllByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(MerchantWebhook.create(MERCHANT_ID, owned, "segredo",
                        java.util.Set.of(WebhookEventType.SALE_PAID))));
        when(httpNotifier.testDelivery(owned, "seg", "SALE_PAID"))
                .thenReturn(HttpMerchantNotifier.DeliveryResult.ok(200));

        HttpMerchantNotifier.DeliveryResult result =
                service.test(MERCHANT_ID, owned, "seg", "SALE_PAID");

        assertThat(result.delivered()).isTrue();
        verify(httpNotifier).testDelivery(owned, "seg", "SALE_PAID");
    }

    @Test
    void testRejectsUrlNotRegisteredToMerchant() {
        // URL pública mas NÃO cadastrada por este lojista → 400, sem POST.
        String foreign = "https://9.9.9.9/hook";
        when(webhookRepository.findAllByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                        "segredo", java.util.Set.of(WebhookEventType.SALE_PAID))));

        assertThatThrownBy(() -> service.test(MERCHANT_ID, foreign, "seg", "SALE_PAID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nao cadastrada");

        verify(httpNotifier, never()).testDelivery(any(), any(), any());
    }

    @Test
    void testRejectsInternalUrlBeforePosting() {
        // SSRF: URL interna rejeitada na validação (400) — o POST nunca acontece,
        // mesmo que o lojista tenha cadastrado outra URL válida.
        when(webhookRepository.findAllByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                        "segredo", java.util.Set.of(WebhookEventType.SALE_PAID))));

        assertThatThrownBy(() -> service.test(MERCHANT_ID, "http://127.0.0.1:8080/hook", "seg", "SALE_PAID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publico");

        verify(httpNotifier, never()).testDelivery(any(), any(), any());
    }

    @Test
    void generateSecretProducesDistinctValues() {
        // Mutation (VoidMethodCall L120): SecureRandom.nextBytes removido → o
        // segredo ficaria zerado (o MESMO valor em toda criação) — cada webhook
        // DEVE ter um segredo distinto.
        WebhookManagementService.CreatedWebhook first =
                service.create(MERCHANT_ID, "https://8.8.8.8/hook-a", List.of("SALE_PAID"));
        WebhookManagementService.CreatedWebhook second =
                service.create(MERCHANT_ID, "https://8.8.8.8/hook-b", List.of("SALE_PAID"));

        assertThat(first.secret()).matches("[0-9a-f]{64}");
        assertThat(first.secret()).isNotEqualTo(second.secret());
    }

    // ---------- round 11 (mutations: list L45, setActive L71, findOwned L97) ----------

    @Test
    void listReturnsMerchantWebhooks() {
        // Mutation (EmptyObjectReturnVals L45): list devolve o que o repositório
        // entrega (o mutante sempre devolveria lista vazia).
        MerchantWebhook webhook = MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                "segredo", java.util.Set.of(WebhookEventType.SALE_PAID));
        when(webhookRepository.findAllByMerchantId(MERCHANT_ID)).thenReturn(List.of(webhook));

        List<MerchantWebhook> result = service.list(MERCHANT_ID);

        assertThat(result).containsExactly(webhook);
    }

    @Test
    void setActiveTogglesOwnedWebhook() {
        // Mutation (NegateConditionals L71): o ternário active ? activate : deactivate
        // NÃO pode ser invertido — o estado salvo deve refletir o pedido.
        MerchantWebhook existing = MerchantWebhook.create(MERCHANT_ID, "https://8.8.8.8/hook",
                "segredo", java.util.Set.of(WebhookEventType.SALE_PAID));
        when(webhookRepository.findByMerchantIdAndId(MERCHANT_ID, existing.id()))
                .thenReturn(java.util.Optional.of(existing));

        service.setActive(MERCHANT_ID, existing.id(), false);
        service.setActive(MERCHANT_ID, existing.id(), true);

        ArgumentCaptor<MerchantWebhook> captor = ArgumentCaptor.forClass(MerchantWebhook.class);
        verify(webhookRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).active()).isFalse();
        assertThat(captor.getAllValues().get(1).active()).isTrue();
    }

    @Test
    void updateOfUnknownWebhookThrowsNotFound() {
        // Mutation (NullReturnVals L97): o supplier do orElseThrow devolve o
        // WebhookNotFoundException (o mutante → null → NPE em vez do 404).
        when(webhookRepository.findByMerchantIdAndId(MERCHANT_ID, UUID.randomUUID()))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.update(MERCHANT_ID, UUID.randomUUID(),
                "https://8.8.8.8/hook", List.of("SALE_PAID"), null))
                .isInstanceOf(WebhookNotFoundException.class);
    }
}
