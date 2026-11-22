package com.foxcash.domain.model.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Configuracao de webhook de saida do lojista (Spec 35) — URL de destino,
 * segredo (criptografado em repouso), eventos assinados e versao da assinatura.
 *
 * <p>{@code signatureVersion} (Spec 148): {@code v2} (default — formato
 * {@code t=<unix>,v1=<hex>}) ou {@code v1} (legado — hex puro), com janela de
 * transicao de 90 dias. Imutavel: ativar/desativar retornam uma nova instancia.
 */
public record MerchantWebhook(UUID id, UUID merchantId, String url, String secret,
                              Set<WebhookEventType> events, boolean active,
                              String signatureVersion,
                              Instant createdAt, Instant updatedAt) {

    public static final String SIGNATURE_VERSION_V2 = "v2";
    public static final String SIGNATURE_VERSION_V1 = "v1";
    public static final String DEFAULT_SIGNATURE_VERSION = SIGNATURE_VERSION_V2;

    public static MerchantWebhook create(UUID merchantId, String url, String secret,
                                         Set<WebhookEventType> events) {
        Instant now = Instant.now();
        return new MerchantWebhook(UUID.randomUUID(), merchantId, url, secret,
                events, true, DEFAULT_SIGNATURE_VERSION, now, now);
    }

    public MerchantWebhook withUrl(String newUrl) {
        return new MerchantWebhook(id, merchantId, newUrl, secret, events, active,
                signatureVersion, createdAt, Instant.now());
    }

    public MerchantWebhook withEvents(Set<WebhookEventType> newEvents) {
        return new MerchantWebhook(id, merchantId, url, secret, newEvents, active,
                signatureVersion, createdAt, Instant.now());
    }

    public MerchantWebhook withSignatureVersion(String newVersion) {
        String version = newVersion == null || newVersion.isBlank()
                ? DEFAULT_SIGNATURE_VERSION : newVersion;
        return new MerchantWebhook(id, merchantId, url, secret, events, active,
                version, createdAt, Instant.now());
    }

    public MerchantWebhook activate() {
        return new MerchantWebhook(id, merchantId, url, secret, events, true,
                signatureVersion, createdAt, Instant.now());
    }

    public MerchantWebhook deactivate() {
        return new MerchantWebhook(id, merchantId, url, secret, events, false,
                signatureVersion, createdAt, Instant.now());
    }

    /** True quando usa o formato v2 (t=<unix>,v1=<hex>) — default. */
    public boolean isV2Signature() {
        return !SIGNATURE_VERSION_V1.equals(signatureVersion);
    }

    /** Webhook ativo e inscrito no tipo de evento. */
    public boolean isSubscribedTo(WebhookEventType type) {
        return active && events.contains(type);
    }
}
