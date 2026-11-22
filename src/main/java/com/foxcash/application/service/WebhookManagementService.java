package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidWebhookEventException;
import com.foxcash.domain.exception.WebhookNotFoundException;
import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantWebhookRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import com.foxcash.infrastructure.notification.HttpMerchantNotifier;
import com.foxcash.infrastructure.security.WebhookUrlValidator;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gestao de webhooks de saida do lojista no portal (Spec 35): CRUD privado por
 * lojista (404 cross-loja sem revelar existencia), segredo gerado no servidor
 * e devolvido uma unica vez, e teste de entrega. A URL e validada contra SSRF
 * ({@link WebhookUrlValidator}) no create/update e na entrega
 * ({@link HttpMerchantNotifier}) — auditoria de seguranca 2026-08-05.
 */
@Service
public class WebhookManagementService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantWebhookRepository webhookRepository;
    private final HttpMerchantNotifier httpNotifier;
    private final NotificationProperties notificationProperties;

    public WebhookManagementService(MerchantWebhookRepository webhookRepository,
                                    HttpMerchantNotifier httpNotifier,
                                    NotificationProperties notificationProperties) {
        this.webhookRepository = webhookRepository;
        this.httpNotifier = httpNotifier;
        this.notificationProperties = notificationProperties;
    }

    public List<MerchantWebhook> list(UUID merchantId) {
        return webhookRepository.findAllByMerchantId(merchantId);
    }

    public CreatedWebhook create(UUID merchantId, String url, List<String> events) {
        String safeUrl = WebhookUrlValidator.validate(url, notificationProperties.isAllowLoopback());
        String secret = generateSecret();
        MerchantWebhook saved = webhookRepository.save(
                MerchantWebhook.create(merchantId, safeUrl, secret, parseEvents(events)));
        return new CreatedWebhook(saved, secret);
    }

    public MerchantWebhook update(UUID merchantId, UUID id, String url, List<String> events,
                                  String signatureVersion) {
        MerchantWebhook existing = findOwned(merchantId, id);
        MerchantWebhook updated = existing;
        if (url != null && !url.isBlank()) {
            String safeUrl = WebhookUrlValidator.validate(url, notificationProperties.isAllowLoopback());
            updated = updated.withUrl(safeUrl);
        }
        if (events != null) {
            updated = updated.withEvents(parseEvents(events));
        }
        // Spec 148: versão da assinatura (v2 default | v1 legado) — null = não
        // alterar. Whitelist: qualquer outro valor é 400 (não existe formato além
        // de v1/v2; um valor inválido persistido viraria v2 silenciosamente).
        if (signatureVersion != null && !signatureVersion.isBlank()) {
            if (!MerchantWebhook.SIGNATURE_VERSION_V1.equals(signatureVersion)
                    && !MerchantWebhook.SIGNATURE_VERSION_V2.equals(signatureVersion)) {
                throw new IllegalArgumentException(
                        "signatureVersion invalida: use '" + MerchantWebhook.SIGNATURE_VERSION_V1
                                + "' ou '" + MerchantWebhook.SIGNATURE_VERSION_V2 + "'");
            }
            updated = updated.withSignatureVersion(signatureVersion);
        }
        return webhookRepository.save(updated);
    }

    public void setActive(UUID merchantId, UUID id, boolean active) {
        MerchantWebhook existing = findOwned(merchantId, id);
        webhookRepository.save(active ? existing.activate() : existing.deactivate());
    }

    public HttpMerchantNotifier.DeliveryResult test(UUID merchantId, String url, String secret, String eventType) {
        // Auditoria 2026-08-05 (B-05): SSRF parcial — teste restrito às URLs de
        // webhooks DO PRÓPRIO lojista. A URL ainda é re-validada aqui (defesa em
        // profundidade; 400 limpo antes do POST) e o POST de fato re-valida no
        // momento da entrega (HttpMerchantNotifier.postRaw). Sem posse, o servidor
        // não vira relay de requisições a hosts arbitrários (mesmo públicos).
        String safeUrl = WebhookUrlValidator.validate(url, notificationProperties.isAllowLoopback());
        requireOwnedUrl(merchantId, safeUrl);
        return httpNotifier.testDelivery(safeUrl, secret, eventType);
    }

    /** A URL de teste precisa ser um webhook cadastrado deste lojista (B-05). */
    private void requireOwnedUrl(UUID merchantId, String url) {
        boolean owned = webhookRepository.findAllByMerchantId(merchantId).stream()
                .anyMatch(webhook -> webhook.url().equals(url));
        if (!owned) {
            throw new IllegalArgumentException(
                    "URL de webhook nao cadastrada para este lojista (teste restrito as proprias URLs)");
        }
    }

    private MerchantWebhook findOwned(UUID merchantId, UUID id) {
        return webhookRepository.findByMerchantIdAndId(merchantId, id)
                .orElseThrow(() -> new WebhookNotFoundException(id));
    }

    /** Valida nomes contra {@link WebhookEventType} (400 invalid_event). */
    private static Set<WebhookEventType> parseEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw new InvalidWebhookEventException("lista vazia");
        }
        return events.stream()
                .map(String::trim)
                .map(name -> {
                    try {
                        return WebhookEventType.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        throw new InvalidWebhookEventException(name);
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Segredo do webhook: 32 bytes aleatorios em hex (devolvido uma unica vez). */
    private static String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** Resultado da criacao: webhook persistido + segredo (unica exposicao). */
    public record CreatedWebhook(MerchantWebhook webhook, String secret) {
    }
}
