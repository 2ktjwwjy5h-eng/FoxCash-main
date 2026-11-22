package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.CannotDeactivateLastAdminException;
import com.foxcash.domain.exception.CannotDeactivateSelfException;
import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.InvalidResetTokenException;
import com.foxcash.domain.exception.MasterAlreadyExistsException;
import com.foxcash.domain.exception.MasterNotFoundException;
import com.foxcash.domain.exception.MasterProfileAccessDeniedException;
import com.foxcash.domain.exception.ResetTokenAlreadyOpenException;
import com.foxcash.domain.exception.TooManyMastersException;
import com.foxcash.domain.exception.TurnstileFailedException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link AuthExceptionHandler}:
 * exceção X → status + code (mesma semântica do contrato congelado em
 * {@code ExceptionMappingContractTest}).
 */
class AuthExceptionHandlerTest {

    private final AuthExceptionHandler handler = new AuthExceptionHandler();

    private void assertMapping(ResponseEntity<ErrorResponse> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void invalidCredentialsMapsTo401() {
        assertMapping(handler.handleInvalidCredentials(new InvalidCredentialsException()), 401, "unauthorized");
    }

    @Test
    void invalidMfaCodeMapsTo401() {
        assertMapping(handler.handleInvalidMfaCode(new InvalidMfaCodeException()), 401, "unauthorized");
    }

    @Test
    void invalidResetTokenMapsTo400() {
        assertMapping(handler.handleInvalidResetToken(new InvalidResetTokenException()), 400, "invalid_token");
    }

    @Test
    void resetTokenAlreadyOpenMapsTo409() {
        assertMapping(handler.handleResetTokenAlreadyOpen(new ResetTokenAlreadyOpenException()),
                409, "reset_token_already_open");
    }

    @Test
    void turnstileFailedMapsTo403() {
        assertMapping(handler.handleTurnstileFailed(new TurnstileFailedException()), 403, "turnstile_failed");
    }

    @Test
    void masterProfileAccessDeniedMapsTo403() {
        assertMapping(handler.handleMasterProfileAccessDenied(new MasterProfileAccessDeniedException()),
                403, "forbidden");
    }

    @Test
    void masterNotFoundMapsTo404() {
        assertMapping(handler.handleMasterNotFound(new MasterNotFoundException(UUID.randomUUID())),
                404, "master_not_found");
    }

    @Test
    void tooManyMastersMapsTo409() {
        assertMapping(handler.handleTooManyMasters(new TooManyMastersException(3)), 409, "too_many_masters");
    }

    @Test
    void masterAlreadyExistsMapsTo409() {
        assertMapping(handler.handleMasterAlreadyExists(new MasterAlreadyExistsException()),
                409, "master_already_exists");
    }

    @Test
    void cannotDeactivateSelfMapsTo409() {
        assertMapping(handler.handleCannotDeactivateSelf(new CannotDeactivateSelfException()),
                409, "cannot_deactivate_self");
    }

    @Test
    void cannotDeactivateLastAdminMapsTo409() {
        assertMapping(handler.handleCannotDeactivateLastAdmin(new CannotDeactivateLastAdminException()),
                409, "cannot_deactivate_last_admin");
    }
}
