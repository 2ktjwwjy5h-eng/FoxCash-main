package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.ChargebackAlreadyExistsException;
import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.PixCodeParseException;
import com.foxcash.domain.exception.PvsAmountException;
import com.foxcash.domain.exception.PvsLimitExceededException;
import com.foxcash.domain.exception.TooManyPendingChargesException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: transação Pix,
 * cobrança, chargeback, idempotência e pagamento via saldo (PVS). Os handlers
 * foram movidos do {@code GlobalExceptionHandler} SEM mudança de comportamento
 * (provado pelo {@code ExceptionMappingContractTest}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("transaction_not_found", ex.getMessage()));
    }

    // ---- Spec 58: cap de cobranças pendentes por lojista ----

    @ExceptionHandler(TooManyPendingChargesException.class)
    public ResponseEntity<ErrorResponse> handleTooManyPendingCharges(TooManyPendingChargesException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("too_many_pending_charges", ex.getMessage()));
    }

    @ExceptionHandler(ChargebackAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleChargebackAlreadyExists(ChargebackAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("chargeback_already_exists", ex.getMessage()));
    }

    @ExceptionHandler(FraudRuleViolationException.class)
    public ResponseEntity<ErrorResponse> handleFraudRuleViolation(FraudRuleViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("idempotency_conflict", ex.getMessage()));
    }

    // ---- Spec 120: Pagamento via Saldo (PVS) ----

    /** Pix Copia e Cola inválido/dinâmico/chave não suportada → 400 com o código específico. */
    @ExceptionHandler(PixCodeParseException.class)
    public ResponseEntity<ErrorResponse> handlePixCodeParse(PixCodeParseException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /** Valor ausente/divergente no PVS → 400 com o código específico. */
    @ExceptionHandler(PvsAmountException.class)
    public ResponseEntity<ErrorResponse> handlePvsAmount(PvsAmountException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /** Teto diário/per-operação do PVS excedido → 409 com o código específico. */
    @ExceptionHandler(PvsLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handlePvsLimitExceeded(PvsLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }
}
