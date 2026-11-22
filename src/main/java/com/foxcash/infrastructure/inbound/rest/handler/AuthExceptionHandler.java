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
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: autenticação,
 * sessão, token de reset, MFA/TOTP, Turnstile e gestão de perfis master. Movido
 * do {@code GlobalExceptionHandler} sem mudança de comportamento (provado pelo
 * {@code ExceptionMappingContractTest}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("unauthorized", ex.getMessage()));
    }

    /** Spec 44: código TOTP/recovery de MFA inválido (401 uniforme, sem oráculo). */
    @ExceptionHandler(InvalidMfaCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMfaCode(InvalidMfaCodeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("unauthorized", ex.getMessage()));
    }

    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidResetToken(InvalidResetTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_token", ex.getMessage()));
    }

    @ExceptionHandler(ResetTokenAlreadyOpenException.class)
    public ResponseEntity<ErrorResponse> handleResetTokenAlreadyOpen(ResetTokenAlreadyOpenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("reset_token_already_open", ex.getMessage()));
    }

    // ---- Spec 63, Fase C: bot mitigation (Turnstile/Cloudflare) ----

    @ExceptionHandler(TurnstileFailedException.class)
    public ResponseEntity<ErrorResponse> handleTurnstileFailed(TurnstileFailedException ex) {
        // Token ausente/inválido no login/cadastro → 403 turnstile_failed
        // (a automação é rejeitada sem chegar ao service).
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("turnstile_failed", ex.getMessage()));
    }

    // ---- Spec 41: gestão de perfis master ----

    @ExceptionHandler(MasterProfileAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMasterProfileAccessDenied(MasterProfileAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("forbidden", ex.getMessage()));
    }

    @ExceptionHandler(MasterNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMasterNotFound(MasterNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("master_not_found", ex.getMessage()));
    }

    @ExceptionHandler(TooManyMastersException.class)
    public ResponseEntity<ErrorResponse> handleTooManyMasters(TooManyMastersException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("too_many_masters", ex.getMessage()));
    }

    @ExceptionHandler(MasterAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleMasterAlreadyExists(MasterAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("master_already_exists", ex.getMessage()));
    }

    @ExceptionHandler(CannotDeactivateSelfException.class)
    public ResponseEntity<ErrorResponse> handleCannotDeactivateSelf(CannotDeactivateSelfException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("cannot_deactivate_self", ex.getMessage()));
    }

    @ExceptionHandler(CannotDeactivateLastAdminException.class)
    public ResponseEntity<ErrorResponse> handleCannotDeactivateLastAdmin(CannotDeactivateLastAdminException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("cannot_deactivate_last_admin", ex.getMessage()));
    }
}
