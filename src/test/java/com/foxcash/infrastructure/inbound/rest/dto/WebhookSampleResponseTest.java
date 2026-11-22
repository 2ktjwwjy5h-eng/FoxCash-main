package com.foxcash.infrastructure.inbound.rest.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do sample de payload do webhook (Spec 119): mock do contrato da Spec
 * 35 SEM PII — o buyerIdHash é máscara fixa e nenhum campo de cliente aparece.
 */
class WebhookSampleResponseTest {

    @Test
    void defaultTypeIsSalePaid() {
        WebhookSampleResponse sample = WebhookSampleResponse.forType(null);

        assertThat(sample.type()).isEqualTo("SALE_PAID");
        assertThat(sample.payload()).containsKey("eventId");
        assertThat(sample.payload()).containsKey("data");
    }

    @Test
    void normalizesTypeToUpperCase() {
        WebhookSampleResponse sample = WebhookSampleResponse.forType("sale_refunded");

        assertThat(sample.type()).isEqualTo("SALE_REFUNDED");
    }

    @Test
    void neverExposesPii() {
        WebhookSampleResponse sample = WebhookSampleResponse.forType("SALE_PAID");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data =
                (java.util.Map<String, Object>) sample.payload().get("data");

        // buyerIdHash SEMPRE mascarado (máscara fixa — nunca o hash real) e
        // nenhum campo de cliente/comprador no sample.
        assertThat(data).containsEntry("buyerIdHash", "******");
        assertThat(sample.payload()).doesNotContainKey("name");
        assertThat(sample.payload()).doesNotContainKey("phone");
        assertThat(sample.payload()).doesNotContainKey("email");
        assertThat(sample.payload()).doesNotContainKey("password");
        assertThat(sample.payload()).doesNotContainKey("secret");
    }
}
