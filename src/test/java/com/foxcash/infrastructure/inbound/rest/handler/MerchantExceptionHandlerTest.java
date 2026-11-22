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
import com.foxcash.domain.model.Money;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link MerchantExceptionHandler}:
 * exceção X → status + code (mesma semântica do contrato congelado em
 * {@code ExceptionMappingContractTest}).
 */
class MerchantExceptionHandlerTest {

    private final MerchantExceptionHandler handler = new MerchantExceptionHandler();

    private void assertMapping(ResponseEntity<ErrorResponse> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void merchantNotFoundMapsTo404() {
        assertMapping(handler.handleMerchantNotFound(new MerchantNotFoundException(UUID.randomUUID())),
                404, "merchant_not_found");
    }

    @Test
    void merchantAlreadyExistsMapsTo409WithoutEchoingDocument() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMerchantAlreadyExists(new MerchantAlreadyExistsException());
        assertMapping(response, 409, "merchant_already_exists");
        assertThat(response.getBody().message()).doesNotContain("52998224725");
    }

    @Test
    void merchantNotActiveMapsTo409() {
        assertMapping(handler.handleMerchantNotActive(new MerchantNotActiveException(UUID.randomUUID())),
                409, "merchant_not_active");
    }

    @Test
    void merchantLoginDeniedMapsTo403() {
        assertMapping(handler.handleMerchantLoginDenied(new MerchantLoginDeniedException(UUID.randomUUID())),
                403, "merchant_not_active");
    }

    @Test
    void insufficientBalanceMapsTo409() {
        assertMapping(handler.handleInsufficientBalance(
                        new InsufficientBalanceException(UUID.randomUUID(), Money.of("10.00"))),
                409, "insufficient_balance");
    }

    @Test
    void withdrawalsBlockedMapsTo409() {
        assertMapping(handler.handleWithdrawalsBlocked(new WithdrawalsBlockedException(UUID.randomUUID())),
                409, "withdrawals_blocked");
    }

    @Test
    void withdrawalNotFoundMapsTo404() {
        assertMapping(handler.handleWithdrawalNotFound(new WithdrawalNotFoundException(UUID.randomUUID())),
                404, "withdrawal_not_found");
    }

    @Test
    void outstandingDebtMapsTo409() {
        assertMapping(handler.handleOutstandingDebt(new OutstandingDebtException(UUID.randomUUID())),
                409, "outstanding_debt");
    }

    @Test
    void invoiceNotFoundMapsTo404() {
        assertMapping(handler.handleInvoiceNotFound(new InvoiceNotFoundException(UUID.randomUUID())),
                404, "invoice_not_found");
    }

    @Test
    void receivableNotFoundMapsTo404() {
        assertMapping(handler.handleReceivableNotFound(new ReceivableNotFoundException(UUID.randomUUID())),
                404, "receivable_not_found");
    }

    @Test
    void auditReasonRequiredMapsTo400() {
        assertMapping(handler.handleAuditReasonRequired(new AuditReasonRequiredException()), 400, "reason_required");
    }

    @Test
    void apiKeyNotFoundMapsTo404() {
        assertMapping(handler.handleApiKeyNotFound(new ApiKeyNotFoundException(UUID.randomUUID())),
                404, "api_key_not_found");
    }

    @Test
    void tooManyApiKeysMapsTo409() {
        assertMapping(handler.handleTooManyApiKeys(new TooManyApiKeysException(5)), 409, "too_many_keys");
    }

    @Test
    void tooManyDestinationsMapsTo409() {
        assertMapping(handler.handleTooManyDestinations(new TooManyDestinationsException(5)), 409, "too_many_destinations");
    }

    @Test
    void destinationNotFoundMapsTo404() {
        assertMapping(handler.handleDestinationNotFound(new DestinationNotFoundException(UUID.randomUUID())),
                404, "destination_not_found");
    }

    @Test
    void destinationInactiveMapsTo409() {
        assertMapping(handler.handleDestinationInactive(new DestinationInactiveException(UUID.randomUUID())),
                409, "destination_inactive");
    }

    @Test
    void giftPresetNotFoundMapsTo404() {
        assertMapping(handler.handleGiftPresetNotFound(new GiftPresetNotFoundException(UUID.randomUUID())),
                404, "gift_preset_not_found");
    }

    @Test
    void riskSnapshotNotFoundMapsTo404() {
        assertMapping(handler.handleRiskSnapshotNotFound(new RiskSnapshotNotFoundException(UUID.randomUUID())),
                404, "risk_snapshot_not_found");
    }

    @Test
    void honeypotBlockedMapsTo409() {
        assertMapping(handler.handleHoneypotBlocked(new HoneypotBlockedException(UUID.randomUUID())),
                409, "honeypot_blocked");
    }

    @Test
    void webhookNotFoundMapsTo404() {
        assertMapping(handler.handleWebhookNotFound(new WebhookNotFoundException(UUID.randomUUID())),
                404, "webhook_not_found");
    }

    @Test
    void invalidWebhookEventMapsTo400() {
        assertMapping(handler.handleInvalidWebhookEvent(new InvalidWebhookEventException("PAID")), 400, "invalid_event");
    }
}
