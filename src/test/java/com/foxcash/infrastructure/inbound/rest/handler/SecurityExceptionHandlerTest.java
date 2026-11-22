package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.InvalidDocumentException;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link SecurityExceptionHandler}:
 * exceção X → status + code (mesma semântica do contrato congelado em
 * {@code ExceptionMappingContractTest}).
 */
class SecurityExceptionHandlerTest {

    private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

    private void assertMapping(ResponseEntity<ErrorResponse> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void merchantAccessDeniedMapsTo403Forbidden() {
        assertMapping(handler.handleMerchantAccessDenied(new MerchantAccessDeniedException()), 403, "forbidden");
    }

    @Test
    void masterPermissionDeniedMapsTo403() {
        assertMapping(handler.handleMasterPermissionDenied(new MasterPermissionDeniedException()), 403, "forbidden");
    }

    @Test
    void masterStepUpRequiredMapsTo403() {
        assertMapping(handler.handleMasterStepUpRequired(new MasterStepUpRequiredException()),
                403, "step_up_required");
    }

    @Test
    void invalidDocumentMapsTo400() {
        assertMapping(handler.handleInvalidDocument(new InvalidDocumentException()), 400, "invalid_argument");
    }
}
