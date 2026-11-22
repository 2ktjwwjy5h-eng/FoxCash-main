package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.port.in.WithdrawalDestinationUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationResponse;
import com.foxcash.infrastructure.inbound.rest.dto.WithdrawalDestinationUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de destinos de saque (Spec 31): merchantId vem do
 * principal (nunca do cliente), a resposta é sempre mascarada (nunca o valor em
 * claro) e erros de domínio propagam para o GlobalExceptionHandler.
 */
class WithdrawalDestinationControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();

    private final WithdrawalDestinationUseCase useCase = mock(WithdrawalDestinationUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private WithdrawalDestinationController controller;

    @BeforeEach
    void setUp() {
        controller = new WithdrawalDestinationController(useCase);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private WithdrawalDestination cpfDestination() {
        return WithdrawalDestination.create(MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, "Minha conta", Instant.now());
    }

    @Test
    void createReturns201WithMaskedResponse() {
        WithdrawalDestination created = cpfDestination();
        when(useCase.create(eq(MERCHANT_ID), any())).thenReturn(created);

        ResponseEntity<WithdrawalDestinationResponse> response = controller.create(authentication,
                new WithdrawalDestinationRequest(WithdrawalDestinationType.PIX_CPF, "52998224725",
                        null, null, null, null, "Minha conta"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        WithdrawalDestinationResponse body = response.getBody();
        assertThat(body.id()).isEqualTo(created.id());
        assertThat(body.type()).isEqualTo("PIX_CPF");
        assertThat(body.status()).isEqualTo("ACTIVE");
        // Máscara presente e o valor em claro NUNCA aparece na resposta.
        assertThat(body.mask()).isEqualTo("529.***.***-25");
        assertThat(body.toString()).doesNotContain("52998224725");
    }

    @Test
    void listReturnsMaskedDestinationsOnly() {
        WithdrawalDestination d = cpfDestination();
        when(useCase.list(MERCHANT_ID)).thenReturn(List.of(d));

        List<WithdrawalDestinationResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).mask()).isEqualTo("529.***.***-25");
        assertThat(response.get(0).toString()).doesNotContain("52998224725");
    }

    @Test
    void updateLabelReturns200Masked() {
        WithdrawalDestination updated = cpfDestination().withLabel("Novo", Instant.now());
        when(useCase.updateLabel(MERCHANT_ID, DESTINATION_ID, "Novo")).thenReturn(updated);

        WithdrawalDestinationResponse response = controller.updateLabel(authentication,
                DESTINATION_ID, new WithdrawalDestinationUpdateRequest("Novo"));

        assertThat(response.label()).isEqualTo("Novo");
        assertThat(response.toString()).doesNotContain("52998224725");
    }

    @Test
    void activateAndDeactivateReturn204() {
        assertThat(controller.activate(authentication, DESTINATION_ID).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.deactivate(authentication, DESTINATION_ID).getStatusCode().value()).isEqualTo(204);
        verify(useCase).setActive(MERCHANT_ID, DESTINATION_ID, true);
        verify(useCase).setActive(MERCHANT_ID, DESTINATION_ID, false);
    }

    @Test
    void invalidFormatPropagatesIllegalArgument() {
        when(useCase.create(eq(MERCHANT_ID), any())).thenThrow(new IllegalArgumentException("Chave Pix CPF invalida"));

        assertThatThrownBy(() -> controller.create(authentication,
                new WithdrawalDestinationRequest(WithdrawalDestinationType.PIX_CPF, "123",
                        null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void destinationOfAnotherMerchantPropagatesNotFound() {
        when(useCase.updateLabel(MERCHANT_ID, DESTINATION_ID, "x"))
                .thenThrow(new DestinationNotFoundException(DESTINATION_ID));

        assertThatThrownBy(() -> controller.updateLabel(authentication, DESTINATION_ID,
                new WithdrawalDestinationUpdateRequest("x")))
                .isInstanceOf(DestinationNotFoundException.class);
    }
}
