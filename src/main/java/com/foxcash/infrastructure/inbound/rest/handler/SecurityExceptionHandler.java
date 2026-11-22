package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.InvalidDocumentException;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: segurança
 * (acesso negado por posse/recurso, permissão do master, step-up MFA exigido e
 * documento inválido). Movido do {@code GlobalExceptionHandler} sem mudança de
 * comportamento (provado pelo {@code ExceptionMappingContractTest}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    @ExceptionHandler(MerchantAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMerchantAccessDenied(MerchantAccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("forbidden", ex.getMessage()));
    }

    /** Spec 69: master sem a permissão da ação (403 forbidden — sem distinção ao atacante). */
    @ExceptionHandler(MasterPermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMasterPermissionDenied(MasterPermissionDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("forbidden", ex.getMessage()));
    }

    /**
     * Spec 69: ação sensível sem step-up MFA válido na sessão → 403
     * {@code step_up_required} (não 401 — o cliente não é derrubado; apenas
     * precisa re-provar posse via {@code POST /master/mfa/step-up}).
     */
    @ExceptionHandler(MasterStepUpRequiredException.class)
    public ResponseEntity<ErrorResponse> handleMasterStepUpRequired(MasterStepUpRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("step_up_required", ex.getMessage()));
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocument(InvalidDocumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_argument", ex.getMessage()));
    }
}
