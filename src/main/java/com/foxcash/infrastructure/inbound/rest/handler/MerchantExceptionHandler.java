package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.ApiKeyNotFoundException;
import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.GiftPresetNotFoundException;
import com.foxcash.domain.exception.HoneypotBlockedException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.InvalidWebhookEventException;
import com.foxcash.domain.exception.InvoiceNotFoundException;
import com.foxcash.domain.exception.MerchantAlreadyExistsException;
import com.foxcash.domain.exception.MerchantLoginDeniedException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.ReceivableNotFoundException;
import com.foxcash.domain.exception.RiskSnapshotNotFoundException;
import com.foxcash.domain.exception.TooManyApiKeysException;
import com.foxcash.domain.exception.TooManyDestinationsException;
import com.foxcash.domain.exception.WebhookNotFoundException;
import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: lojista (conta),
 * wallet, saques, faturamento (billing/receivables), destinos de saque e
 * credenciais da API. Movido do {@code GlobalExceptionHandler} sem mudança de
 * comportamento (provado pelo {@code ExceptionMappingContractTest}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantExceptionHandler {

    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMerchantNotFound(MerchantNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("merchant_not_found", ex.getMessage()));
    }

    @ExceptionHandler(MerchantAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleMerchantAlreadyExists(MerchantAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("merchant_already_exists", ex.getMessage()));
    }

    @ExceptionHandler(MerchantNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleMerchantNotActive(MerchantNotActiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("merchant_not_active", ex.getMessage()));
    }

    @ExceptionHandler(MerchantLoginDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMerchantLoginDenied(MerchantLoginDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("merchant_not_active", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("insufficient_balance", ex.getMessage()));
    }

    @ExceptionHandler(WithdrawalsBlockedException.class)
    public ResponseEntity<ErrorResponse> handleWithdrawalsBlocked(WithdrawalsBlockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("withdrawals_blocked", ex.getMessage()));
    }

    @ExceptionHandler(WithdrawalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWithdrawalNotFound(WithdrawalNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("withdrawal_not_found", ex.getMessage()));
    }

    @ExceptionHandler(OutstandingDebtException.class)
    public ResponseEntity<ErrorResponse> handleOutstandingDebt(OutstandingDebtException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("outstanding_debt", ex.getMessage()));
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInvoiceNotFound(InvoiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("invoice_not_found", ex.getMessage()));
    }

    @ExceptionHandler(ReceivableNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReceivableNotFound(ReceivableNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("receivable_not_found", ex.getMessage()));
    }

    @ExceptionHandler(AuditReasonRequiredException.class)
    public ResponseEntity<ErrorResponse> handleAuditReasonRequired(AuditReasonRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("reason_required", ex.getMessage()));
    }

    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyNotFound(ApiKeyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("api_key_not_found", ex.getMessage()));
    }

    @ExceptionHandler(TooManyApiKeysException.class)
    public ResponseEntity<ErrorResponse> handleTooManyApiKeys(TooManyApiKeysException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("too_many_keys", ex.getMessage()));
    }

    @ExceptionHandler(TooManyDestinationsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyDestinations(TooManyDestinationsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("too_many_destinations", ex.getMessage()));
    }

    @ExceptionHandler(DestinationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDestinationNotFound(DestinationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("destination_not_found", ex.getMessage()));
    }

    @ExceptionHandler(DestinationInactiveException.class)
    public ResponseEntity<ErrorResponse> handleDestinationInactive(DestinationInactiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("destination_inactive", ex.getMessage()));
    }

    @ExceptionHandler(GiftPresetNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGiftPresetNotFound(GiftPresetNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("gift_preset_not_found", ex.getMessage()));
    }

    @ExceptionHandler(RiskSnapshotNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRiskSnapshotNotFound(RiskSnapshotNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("risk_snapshot_not_found", ex.getMessage()));
    }

    /** Spec 98 (D4.5): saque de lojista honeypot bloqueado (nenhum — vendedor ou master). */
    @ExceptionHandler(HoneypotBlockedException.class)
    public ResponseEntity<ErrorResponse> handleHoneypotBlocked(HoneypotBlockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("honeypot_blocked", ex.getMessage()));
    }

    @ExceptionHandler(WebhookNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWebhookNotFound(WebhookNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("webhook_not_found", ex.getMessage()));
    }

    @ExceptionHandler(InvalidWebhookEventException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhookEvent(InvalidWebhookEventException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_event", ex.getMessage()));
    }
}
