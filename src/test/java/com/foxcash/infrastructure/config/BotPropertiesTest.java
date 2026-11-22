package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link BotProperties} (Spec 74): defaults seguros (webhook base
 * vazio = dev, reconciliacao ativa, gateway Discord OFF, base URLs reais das
 * APIs) e overrides via setter (binding por env {@code FOXCASH_BOTS_*}).
 */
class BotPropertiesTest {

    @Test
    void defaultsAreDevSafe() {
        BotProperties properties = new BotProperties();

        assertThat(properties.getWebhookPublicBaseUrl()).isEmpty();
        assertThat(properties.isReconcileEnabled()).isTrue();
        assertThat(properties.getReconcileCron()).isEqualTo("0 */5 * * * *");
        assertThat(properties.isDiscordGatewayEnabled()).isFalse();
        assertThat(properties.getTelegramApiBaseUrl()).isEqualTo("https://api.telegram.org");
        assertThat(properties.getDiscordApiBaseUrl()).isEqualTo("https://discord.com/api/v10");
        assertThat(properties.getTelegramCdnChannelId()).isEmpty();
        assertThat(properties.getRegisteringTimeoutMinutes()).isEqualTo(30);
        assertThat(properties.getHealthTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void overridesAreRespected() {
        BotProperties properties = new BotProperties();
        properties.setWebhookPublicBaseUrl("https://api.foxcash.example.com");
        properties.setReconcileEnabled(false);
        properties.setDiscordGatewayEnabled(true);
        properties.setTelegramApiBaseUrl("http://localhost:9001");
        properties.setTelegramCdnChannelId("-1001234567890");

        assertThat(properties.getWebhookPublicBaseUrl()).isEqualTo("https://api.foxcash.example.com");
        assertThat(properties.isReconcileEnabled()).isFalse();
        assertThat(properties.isDiscordGatewayEnabled()).isTrue();
        assertThat(properties.getTelegramApiBaseUrl()).isEqualTo("http://localhost:9001");
        assertThat(properties.getTelegramCdnChannelId()).isEqualTo("-1001234567890");
    }
}
