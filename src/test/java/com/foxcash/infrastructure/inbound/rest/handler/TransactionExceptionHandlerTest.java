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
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link TransactionExceptionHandler}:
 * exceção X → status + code (mesma semântica do contrato congelado em
 * {@code ExceptionMappingContractTest}).
 */
class TransactionExceptionHandlerTest {

    private final TransactionExceptionHandler handler = new TransactionExceptionHandler();

    private void assertMapping(ResponseEntity<ErrorResponse> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void transactionNotFoundMapsTo404() {
        assertMapping(handler.handleTransactionNotFound(new TransactionNotFoundException(UUID.randomUUID())),
                404, "transaction_not_found");
    }

    @Test
    void tooManyPendingChargesMapsTo409() {
        assertMapping(handler.handleTooManyPendingCharges(new TooManyPendingChargesException(10)),
                409, "too_many_pending_charges");
    }

    @Test
    void chargebackAlreadyExistsMapsTo409() {
        assertMapping(handler.handleChargebackAlreadyExists(new ChargebackAlreadyExistsException(UUID.randomUUID())),
                409, "chargeback_already_exists");
    }

    @Test
    void fraudRuleViolationUsesExceptionCode() {
        assertMapping(handler.handleFraudRuleViolation(
                        new FraudRuleViolationException("fraud_buyer_blocked", "Comprador bloqueado")),
                409, "fraud_buyer_blocked");
    }

    @Test
    void idempotencyConflictMapsTo409() {
        assertMapping(handler.handleIdempotencyConflict(new IdempotencyConflictException("cob-key-1")),
                409, "idempotency_conflict");
    }

    @Test
    void pixCodeParseUsesExceptionCode() {
        assertMapping(handler.handlePixCodeParse(new PixCodeParseException("invalid_pix_code", "x")),
                400, "invalid_pix_code");
    }

    @Test
    void pvsAmountUsesExceptionCode() {
        assertMapping(handler.handlePvsAmount(new PvsAmountException("pvs_amount_mismatch", "x")),
                400, "pvs_amount_mismatch");
    }

    @Test
    void pvsLimitUsesExceptionCode() {
        assertMapping(handler.handlePvsLimitExceeded(new PvsLimitExceededException("pvs_daily_cap_exceeded", "x")),
                409, "pvs_daily_cap_exceeded");
    }
}
