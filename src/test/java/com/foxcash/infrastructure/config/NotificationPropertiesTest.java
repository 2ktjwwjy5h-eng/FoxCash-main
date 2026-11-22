package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes do fail-fast de seguranca do bloco {@code reset-token} (Spec 45): em
 * producao {@code deliverer=log} e rejeitado no boot — o token de recuperacao e
 * credencial de conta e nunca vai para logs de producao.
 */
class NotificationPropertiesTest {

    @Test
    void delivererLogIsRejectedInProdProfile() {
        NotificationProperties properties = new NotificationProperties();
        properties.setActiveProfiles("prod");
        properties.getResetToken().setDeliverer("log");

        assertThrows(IllegalStateException.class, properties::validate,
                "deliverer=log em prod deve falhar no boot (fail-fast de seguranca)");
    }

    @Test
    void delivererSmtpIsAcceptedInProdProfile() {
        NotificationProperties properties = new NotificationProperties();
        properties.setActiveProfiles("prod");
        properties.getResetToken().setDeliverer("smtp");

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void delivererLogIsAcceptedOutsideProd() {
        NotificationProperties properties = new NotificationProperties();
        properties.setActiveProfiles("dev");
        properties.getResetToken().setDeliverer("log");

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void noProfileDefaultsToDevBehavior() {
        NotificationProperties properties = new NotificationProperties();
        properties.setActiveProfiles("");
        properties.getResetToken().setDeliverer("log");

        assertDoesNotThrow(properties::validate);
    }

    // ---------- Spec 53: webhook de operador (JOB_FAILED) ----------

    @Test
    void operatorWebhookRequiresUrlAndSecretTogether() {
        NotificationProperties properties = new NotificationProperties();
        properties.setOperatorWebhookUrl("https://ops.example.com/hook");

        assertThrows(IllegalStateException.class, properties::validate,
                "URL sem segredo deve falhar no boot (fail-fast Spec 53)");
    }

    @Test
    void operatorWebhookUrlAndSecretTogetherAreAccepted() {
        NotificationProperties properties = new NotificationProperties();
        properties.setOperatorWebhookUrl("https://ops.example.com/hook");
        properties.setOperatorWebhookSecret("secret");

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void operatorWebhookNotConfiguredWhenUrlBlank() {
        NotificationProperties properties = new NotificationProperties();
        properties.setOperatorWebhookUrl("");
        properties.setOperatorWebhookSecret("");

        assertThat(properties.isOperatorWebhookConfigured()).isFalse();
        assertDoesNotThrow(properties::validate);
    }

    // ---------- Teto absoluto do lote do dispatcher de outbox (Etapa 1, pos-Fase 2A) ----------

    @Test
    void maxBatchSizeAboveSafeLimitIsRejected() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchSize(501);

        assertThrows(IllegalStateException.class, properties::validate,
                "lote acima do teto absoluto (500 eventos/run) deve falhar no boot — o teto limita "
                        + "a duracao do pior caso da run e o tamanho da query");
    }

    @Test
    void maxBatchSizeAtSafeLimitIsAccepted() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchSize(500);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void maxBatchSize240UsedInProductionIsAccepted() {
        // Regressao Etapa 1: o valor endurecido pos-incidente (240) continua
        // aceito apos a Spec 65 elevar o teto para 500 (meio-termo).
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchSize(240);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void maxBatchSize200UsedInProductionIsAccepted() {
        // Regressao Etapa 1: producao usa FOXCASH_NOTIFICATION_MAX_BATCH_SIZE=200 —
        // o desacoplamento do teto do lock (PT40M->PT5M) NAO pode quebrar o boot.
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchSize(200);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void defaultMaxBatchSizeIsAccepted() {
        NotificationProperties properties = new NotificationProperties();

        assertThat(properties.getMaxBatchSize()).isEqualTo(500);
        assertThat(properties.getMaxBatchesPerRun()).isEqualTo(10);
        assertDoesNotThrow(properties::validate);
    }

    // ---------- Spec 65: teto de lotes por run (loop do dispatcher) ----------

    @Test
    void maxBatchesPerRunBelowOneIsRejected() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchesPerRun(0);

        assertThrows(IllegalStateException.class, properties::validate,
                "o dispatcher processa no minimo 1 lote por run");
    }

    @Test
    void maxBatchesPerRunConfiguredIsAccepted() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxBatchesPerRun(3);

        assertThat(properties.getMaxBatchesPerRun()).isEqualTo(3);
        assertDoesNotThrow(properties::validate);
    }
}
