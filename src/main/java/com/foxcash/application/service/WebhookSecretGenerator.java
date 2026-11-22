package com.foxcash.application.service;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gera o {@code webhook_secret} do bot (Spec 74) — 32 bytes aleatórios (hex).
 * O segredo compõe a URL pública `/webhooks/telegram/{id}/{secret}` e o header
 * {@code X-Telegram-Bot-Api-Secret-Token} do Telegram; nunca é o id do bot e
 * nunca é persistido em claro (só o hash SHA-256).
 */
public final class WebhookSecretGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecretGenerator() {
    }

    /** 32 bytes aleatórios em hex (64 chars). */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
