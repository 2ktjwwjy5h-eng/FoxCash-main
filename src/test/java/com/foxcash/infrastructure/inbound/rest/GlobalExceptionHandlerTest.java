package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link GlobalExceptionHandler} (Spec 18): mapeamento de status
 * padronizado para os handlers GENÉRICOS/framework (400/404/405/409 + fallback
 * 500 com métrica). Após a Spec 136 (refactor de god classes), os handlers de
 * DOMÍNIO vivem nos especialistas {@code handler/*} e têm teste próprio
 * ({@code *HandlerTest}) + o contrato congelado em {@code ExceptionMappingContractTest}.
 */
class GlobalExceptionHandlerTest {

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(meterRegistry);

    @Test
    void missingParameterMapsTo400InsteadOf500() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(
                new MissingServletRequestParameterException("month", "YearMonth"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("invalid_argument");
    }

    @Test
    void unreadableBodyMapsTo400() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("corpo ilegível"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void constraintViolationMapsTo400() {
        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(
                new ConstraintViolationException("violação", null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- Auditoria 2026-08-05 (B-01): 400/409 não ecoam ex.getMessage() ----------

    @Test
    void illegalArgumentMapsTo400WithGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException("senha deve ter entre 8 e 72 caracteres"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("invalid_argument");
        // A mensagem interna (validação/domínio) não é ecoada ao cliente.
        assertThat(response.getBody().message()).doesNotContain("senha");
    }

    @Test
    void illegalStateMapsTo409WithGenericMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(
                new IllegalStateException("ConstraintViolationException: uq_merchants_email"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("invalid_state");
        // Detalhe interno (constraint/estado) nunca chega ao cliente.
        assertThat(response.getBody().message()).doesNotContain("uq_merchants_email");
    }

    @Test
    void unknownPathMapsTo404InsteadOf500() {
        // Auditoria 2026-08-05 (M-A1): rota inexistente com auth válida → 404
        // (antes caía em handleUnexpected → 500 + stack trace em ERROR).
        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "api/v1/nao-existe"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("not_found");
        assertThat(response.getBody().message()).doesNotContain("NoResourceFoundException");
    }

    @Test
    void methodNotSupportedMapsTo405() {
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET"));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().code()).isEqualTo("method_not_allowed");
    }

    @Test
    void validationErrorMapsTo400WithFieldMessage() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "command");
        binding.addError(new FieldError("command", "amount", "must be positive"));
        ResponseEntity<ErrorResponse> response = handler.handleValidation(
                new MethodArgumentNotValidException(null, binding));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("validation_error");
        assertThat(response.getBody().message()).contains("amount").contains("must be positive");
    }

    @Test
    void unexpectedMapsTo500WithGenericMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/tx-1");
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("internal_error");
        // Spec 126: métrica de 5xx por rota normalizada (alerta proativo).
        assertThat(meterRegistry.get("foxcash_http_5xx_total")
                .tag("route", "/api/v1/transactions/tx-1").counter().count()).isEqualTo(1);
    }
}
