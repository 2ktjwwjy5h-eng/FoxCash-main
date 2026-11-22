package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-27 (revisão de segurança 2026-08-11): o {@code note} da conclusão de ação
 * de entrega é limitado a 255 caracteres — a coluna
 * {@code plan_delivery_actions_exec.note} é {@code VARCHAR(255)}; sem o teto,
 * um note longo virava DataException (500). Mesmo padrão do
 * {@link DeliveryAccessRequest}.
 */
class DeliveryCompleteRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void noteLongerThan255IsRejected() {
        DeliveryCompleteRequest request =
                new DeliveryCompleteRequest(DeliveryActionStatus.DONE, "x".repeat(256));

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void noteAtExactLimitIsAccepted() {
        DeliveryCompleteRequest request =
                new DeliveryCompleteRequest(DeliveryActionStatus.DONE, "x".repeat(255));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void nullNoteIsAccepted() {
        // note é opcional (o bot pode concluir sem motivo).
        DeliveryCompleteRequest request =
                new DeliveryCompleteRequest(DeliveryActionStatus.FAILED, null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
