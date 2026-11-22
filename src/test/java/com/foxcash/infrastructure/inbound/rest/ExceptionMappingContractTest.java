package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.ApiKeyNotFoundException;
import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.BotInactiveException;
import com.foxcash.domain.exception.BotsNotAllowedForScopeException;
import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.CannotDeactivateLastAdminException;
import com.foxcash.domain.exception.CannotDeactivateSelfException;
import com.foxcash.domain.exception.ChargebackAlreadyExistsException;
import com.foxcash.domain.exception.DeliveryActionNotFoundException;
import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.DiscordApiException;
import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.GiftPresetNotFoundException;
import com.foxcash.domain.exception.HoneypotBlockedException;
import com.foxcash.domain.exception.IdempotencyConflictException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.InvalidBotStateException;
import com.foxcash.domain.exception.InvalidBroadcastStateException;
import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.exception.InvalidDocumentException;
import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.InvalidProfileNameException;
import com.foxcash.domain.exception.InvalidProfileScopeException;
import com.foxcash.domain.exception.InvalidResetTokenException;
import com.foxcash.domain.exception.InvalidWebhookEventException;
import com.foxcash.domain.exception.InvoiceNotFoundException;
import com.foxcash.domain.exception.LeadBlockedException;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.exception.MasterAlreadyExistsException;
import com.foxcash.domain.exception.MasterNotFoundException;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.exception.MasterProfileAccessDeniedException;
import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.MerchantAlreadyExistsException;
import com.foxcash.domain.exception.MerchantLoginDeniedException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.MessageSendFailedException;
import com.foxcash.domain.exception.OperationProfileNotFoundException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.PixCodeParseException;
import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.PspTimeoutException;
import com.foxcash.domain.exception.PvsAmountException;
import com.foxcash.domain.exception.PvsLimitExceededException;
import com.foxcash.domain.exception.ReceivableNotFoundException;
import com.foxcash.domain.exception.ResetTokenAlreadyOpenException;
import com.foxcash.domain.exception.RiskSnapshotNotFoundException;
import com.foxcash.domain.exception.RouteNotAllowedForScopeException;
import com.foxcash.domain.exception.StoreAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.exception.StoreButtonNotFoundException;
import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.exception.TelegramApiException;
import com.foxcash.domain.exception.TooManyApiKeysException;
import com.foxcash.domain.exception.TooManyButtonsException;
import com.foxcash.domain.exception.TooManyDestinationsException;
import com.foxcash.domain.exception.TooManyMastersException;
import com.foxcash.domain.exception.TooManyPendingChargesException;
import com.foxcash.domain.exception.TrackingProfileNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.exception.TurnstileFailedException;
import com.foxcash.domain.exception.UnknownGatewayRouteException;
import com.foxcash.domain.exception.WebhookNotFoundException;
import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.domain.model.Money;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import com.foxcash.infrastructure.inbound.rest.handler.AuthExceptionHandler;
import com.foxcash.infrastructure.inbound.rest.handler.MerchantExceptionHandler;
import com.foxcash.infrastructure.inbound.rest.handler.PixProviderExceptionHandler;
import com.foxcash.infrastructure.inbound.rest.handler.SecurityExceptionHandler;
import com.foxcash.infrastructure.inbound.rest.handler.StoreExceptionHandler;
import com.foxcash.infrastructure.inbound.rest.handler.TransactionExceptionHandler;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (T0) — Contrato do mapeamento de exceções → HTTP.
 *
 * <p>Congela o comportamento do {@link GlobalExceptionHandler} ANTES do refactor
 * (todos os 76+ handlers: status + code por exceção). Após a divisão em
 * especialistas ({@code handler/*}), a MESMA tabela de asserções continua verde —
 * a prova de que o refactor é comportamentalmente idêntico (critério C3).
 *
 * <p>O contrato é a lista {@link #CONTRACT} (exceção → status → code). O método
 * {@link #invoke(Exception, MockHttpServletRequest)} roteia para o advice
 * correto — ANTES do refactor tudo vive no {@link GlobalExceptionHandler}; DEPOIS
 * os handlers de domínio vivem nos especialistas e apenas o alvo do roteamento
 * muda (mecânico, documentado no task.md §Implementação). A TABELA não muda.
 */
class ExceptionMappingContractTest {

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(meterRegistry);
    private final TransactionExceptionHandler transaction = new TransactionExceptionHandler();
    private final PixProviderExceptionHandler pixProvider = new PixProviderExceptionHandler();
    private final MerchantExceptionHandler merchant = new MerchantExceptionHandler();
    private final StoreExceptionHandler store = new StoreExceptionHandler();
    private final AuthExceptionHandler auth = new AuthExceptionHandler();
    private final SecurityExceptionHandler security = new SecurityExceptionHandler();

    private record Case(String name, java.util.function.Supplier<Exception> factory,
                        HttpStatus status, String code) {
    }

    /** Contrato congelado: exceção → (status, code). Extraída de {@code GlobalExceptionHandler} (2026-08-16). */
    private static final List<Case> CONTRACT = List.of(
            // ---- Transaction / pagamento (Spec 136: TransactionExceptionHandler) ----
            new Case("transaction_not_found", () -> new TransactionNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "transaction_not_found"),
            new Case("too_many_pending_charges", () -> new TooManyPendingChargesException(10), HttpStatus.CONFLICT, "too_many_pending_charges"),
            new Case("chargeback_already_exists", () -> new ChargebackAlreadyExistsException(UUID.randomUUID()), HttpStatus.CONFLICT, "chargeback_already_exists"),
            new Case("fraud_rule_violation", () -> new FraudRuleViolationException("fraud_buyer_blocked", "x"), HttpStatus.CONFLICT, "fraud_buyer_blocked"),
            new Case("idempotency_conflict", () -> new IdempotencyConflictException("key-1"), HttpStatus.CONFLICT, "idempotency_conflict"),
            new Case("pix_code_parse", () -> new PixCodeParseException("invalid_pix_code", "x"), HttpStatus.BAD_REQUEST, "invalid_pix_code"),
            new Case("pvs_amount", () -> new PvsAmountException("pvs_amount_mismatch", "x"), HttpStatus.BAD_REQUEST, "pvs_amount_mismatch"),
            new Case("pvs_limit", () -> new PvsLimitExceededException("pvs_daily_cap_exceeded", "x"), HttpStatus.CONFLICT, "pvs_daily_cap_exceeded"),
            // ---- PSP (Spec 136: PixProviderExceptionHandler) ----
            new Case("psp_timeout", () -> new PspTimeoutException("timeout", new RuntimeException()), HttpStatus.BAD_GATEWAY, "psp_timeout"),
            // ---- Merchant / conta / wallet / saque / billing / destinos / credenciais ----
            new Case("merchant_not_found", () -> new MerchantNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "merchant_not_found"),
            new Case("merchant_already_exists", MerchantAlreadyExistsException::new, HttpStatus.CONFLICT, "merchant_already_exists"),
            new Case("merchant_not_active", () -> new MerchantNotActiveException(UUID.randomUUID()), HttpStatus.CONFLICT, "merchant_not_active"),
            new Case("merchant_login_denied", () -> new MerchantLoginDeniedException(UUID.randomUUID()), HttpStatus.FORBIDDEN, "merchant_not_active"),
            new Case("merchant_access_denied", MerchantAccessDeniedException::new, HttpStatus.FORBIDDEN, "forbidden"),
            new Case("insufficient_balance", () -> new InsufficientBalanceException(UUID.randomUUID(), Money.of("10.00")), HttpStatus.CONFLICT, "insufficient_balance"),
            new Case("withdrawals_blocked", () -> new WithdrawalsBlockedException(UUID.randomUUID()), HttpStatus.CONFLICT, "withdrawals_blocked"),
            new Case("withdrawal_not_found", () -> new WithdrawalNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "withdrawal_not_found"),
            new Case("outstanding_debt", () -> new OutstandingDebtException(UUID.randomUUID()), HttpStatus.CONFLICT, "outstanding_debt"),
            new Case("invoice_not_found", () -> new InvoiceNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "invoice_not_found"),
            new Case("receivable_not_found", () -> new ReceivableNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "receivable_not_found"),
            new Case("reason_required", AuditReasonRequiredException::new, HttpStatus.BAD_REQUEST, "reason_required"),
            new Case("api_key_not_found", () -> new ApiKeyNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "api_key_not_found"),
            new Case("too_many_keys", () -> new TooManyApiKeysException(5), HttpStatus.CONFLICT, "too_many_keys"),
            new Case("too_many_destinations", () -> new TooManyDestinationsException(5), HttpStatus.CONFLICT, "too_many_destinations"),
            new Case("destination_not_found", () -> new DestinationNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "destination_not_found"),
            new Case("destination_inactive", () -> new DestinationInactiveException(UUID.randomUUID()), HttpStatus.CONFLICT, "destination_inactive"),
            new Case("gift_preset_not_found", () -> new GiftPresetNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "gift_preset_not_found"),
            new Case("risk_snapshot_not_found", () -> new RiskSnapshotNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "risk_snapshot_not_found"),
            new Case("honeypot_blocked", () -> new HoneypotBlockedException(UUID.randomUUID()), HttpStatus.CONFLICT, "honeypot_blocked"),
            new Case("webhook_not_found", () -> new WebhookNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "webhook_not_found"),
            new Case("invalid_event", () -> new InvalidWebhookEventException("PAID"), HttpStatus.BAD_REQUEST, "invalid_event"),
            // ---- Loja white-label: store / botões / mensagens / planos / entregas / bots / CRM / broadcast / tracking / perfis ----
            new Case("store_not_found", StoreNotFoundException::new, HttpStatus.NOT_FOUND, "store_not_found"),
            new Case("store_already_exists", StoreAlreadyExistsException::new, HttpStatus.CONFLICT, "store_already_exists"),
            new Case("button_not_found", StoreButtonNotFoundException::new, HttpStatus.NOT_FOUND, "button_not_found"),
            new Case("too_many_buttons", TooManyButtonsException::new, HttpStatus.CONFLICT, "too_many_buttons"),
            new Case("message_not_found", StoreMessageNotFoundException::new, HttpStatus.NOT_FOUND, "message_not_found"),
            new Case("plan_not_found", PlanNotFoundException::new, HttpStatus.NOT_FOUND, "plan_not_found"),
            new Case("plan_inactive", PlanInactiveException::new, HttpStatus.CONFLICT, "plan_inactive"),
            new Case("delivery_not_found", DeliveryNotFoundException::new, HttpStatus.NOT_FOUND, "delivery_not_found"),
            new Case("delivery_action_not_found", DeliveryActionNotFoundException::new, HttpStatus.NOT_FOUND, "delivery_action_not_found"),
            new Case("lead_not_found", () -> new LeadNotFoundException("lead"), HttpStatus.NOT_FOUND, "lead_not_found"),
            new Case("lead_blocked", () -> new LeadBlockedException("blocked"), HttpStatus.CONFLICT, "lead_blocked"),
            new Case("message_send_failed", () -> new MessageSendFailedException("fail", new RuntimeException()), HttpStatus.BAD_GATEWAY, "message_send_failed"),
            new Case("bot_not_found", StoreBotNotFoundException::new, HttpStatus.NOT_FOUND, "bot_not_found"),
            new Case("bot_already_exists", StoreBotAlreadyExistsException::new, HttpStatus.CONFLICT, "bot_already_exists"),
            new Case("invalid_bot_state", () -> new InvalidBotStateException("x"), HttpStatus.CONFLICT, "invalid_bot_state"),
            new Case("bot_inactive", BotInactiveException::new, HttpStatus.CONFLICT, "bot_inactive"),
            new Case("telegram_api_error", () -> new TelegramApiException("x"), HttpStatus.BAD_REQUEST, "telegram_api_error"),
            new Case("discord_api_error", () -> new DiscordApiException("x"), HttpStatus.BAD_REQUEST, "discord_api_error"),
            new Case("broadcast_not_found", BroadcastNotFoundException::new, HttpStatus.NOT_FOUND, "broadcast_not_found"),
            new Case("invalid_broadcast_state", InvalidBroadcastStateException::new, HttpStatus.CONFLICT, "invalid_broadcast_state"),
            new Case("tracking_profile_not_found", TrackingProfileNotFoundException::new, HttpStatus.NOT_FOUND, "tracking_profile_not_found"),
            new Case("profile_not_found", () -> new OperationProfileNotFoundException("x"), HttpStatus.NOT_FOUND, "profile_not_found"),
            new Case("unknown_gateway_route", () -> new UnknownGatewayRouteException("x"), HttpStatus.BAD_REQUEST, "unknown_gateway_route"),
            new Case("invalid_name", () -> new InvalidProfileNameException("x"), HttpStatus.BAD_REQUEST, "invalid_name"),
            new Case("invalid_scope", () -> new InvalidProfileScopeException("x"), HttpStatus.BAD_REQUEST, "invalid_scope"),
            new Case("route_not_allowed_for_scope", () -> new RouteNotAllowedForScopeException("x"), HttpStatus.BAD_REQUEST, "route_not_allowed_for_scope"),
            new Case("bots_not_allowed_for_scope", () -> new BotsNotAllowedForScopeException("x"), HttpStatus.BAD_REQUEST, "bots_not_allowed_for_scope"),
            // ---- Auth / sessão / token / MFA / master ----
            new Case("unauthorized", InvalidCredentialsException::new, HttpStatus.UNAUTHORIZED, "unauthorized"),
            new Case("invalid_mfa_code", InvalidMfaCodeException::new, HttpStatus.UNAUTHORIZED, "unauthorized"),
            new Case("invalid_token", InvalidResetTokenException::new, HttpStatus.BAD_REQUEST, "invalid_token"),
            new Case("reset_token_already_open", ResetTokenAlreadyOpenException::new, HttpStatus.CONFLICT, "reset_token_already_open"),
            new Case("turnstile_failed", TurnstileFailedException::new, HttpStatus.FORBIDDEN, "turnstile_failed"),
            new Case("master_profile_access_denied", MasterProfileAccessDeniedException::new, HttpStatus.FORBIDDEN, "forbidden"),
            new Case("master_not_found", () -> new MasterNotFoundException(UUID.randomUUID()), HttpStatus.NOT_FOUND, "master_not_found"),
            new Case("too_many_masters", () -> new TooManyMastersException(3), HttpStatus.CONFLICT, "too_many_masters"),
            new Case("master_already_exists", MasterAlreadyExistsException::new, HttpStatus.CONFLICT, "master_already_exists"),
            new Case("cannot_deactivate_self", CannotDeactivateSelfException::new, HttpStatus.CONFLICT, "cannot_deactivate_self"),
            new Case("cannot_deactivate_last_admin", CannotDeactivateLastAdminException::new, HttpStatus.CONFLICT, "cannot_deactivate_last_admin"),
            // ---- Security / acesso ----
            new Case("master_permission_denied", MasterPermissionDeniedException::new, HttpStatus.FORBIDDEN, "forbidden"),
            new Case("step_up_required", MasterStepUpRequiredException::new, HttpStatus.FORBIDDEN, "step_up_required"),
            new Case("invalid_document", InvalidDocumentException::new, HttpStatus.BAD_REQUEST, "invalid_argument"),
            // ---- Genérico / framework (permanecem no GlobalExceptionHandler) ----
            new Case("missing_param", () -> new MissingServletRequestParameterException("month", "YearMonth"), HttpStatus.BAD_REQUEST, "invalid_argument"),
            new Case("type_mismatch", () -> new MethodArgumentTypeMismatchException("x", int.class, "size", null, null), HttpStatus.BAD_REQUEST, "invalid_argument"),
            new Case("unreadable_body", () -> new HttpMessageNotReadableException("x"), HttpStatus.BAD_REQUEST, "invalid_argument"),
            new Case("constraint_violation", () -> new ConstraintViolationException("x", null), HttpStatus.BAD_REQUEST, "invalid_argument"),
            new Case("method_not_allowed", () -> new HttpRequestMethodNotSupportedException("GET"), HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed"),
            new Case("no_resource", () -> new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "api/v1/nao-existe"), HttpStatus.NOT_FOUND, "not_found"),
            new Case("illegal_state", () -> new IllegalStateException("uq_merchants_email"), HttpStatus.CONFLICT, "invalid_state"),
            new Case("illegal_argument", () -> new IllegalArgumentException("senha deve ter entre 8 e 72"), HttpStatus.BAD_REQUEST, "invalid_argument"),
            new Case("validation_error", () -> {
                BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "command");
                binding.addError(new FieldError("command", "amount", "must be positive"));
                return new MethodArgumentNotValidException(null, binding);
            }, HttpStatus.BAD_REQUEST, "validation_error"),
            new Case("unexpected", () -> new RuntimeException("boom"), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error")
    );

    /**
     * Roteia a exceção para o advice correto e devolve a resposta.
     * ANTES do refactor: todos os handlers vivem no {@link GlobalExceptionHandler}.
     * DEPOIS: os handlers de domínio vivem nos especialistas {@code handler/*}
     * (a tabela {@link #CONTRACT} não muda — apenas o alvo deste roteamento).
     */
    private ResponseEntity<ErrorResponse> invoke(Exception ex, MockHttpServletRequest request) {
        // Transaction / pagamento
        if (ex instanceof TransactionNotFoundException e) return transaction.handleTransactionNotFound(e);
        if (ex instanceof TooManyPendingChargesException e) return transaction.handleTooManyPendingCharges(e);
        if (ex instanceof ChargebackAlreadyExistsException e) return transaction.handleChargebackAlreadyExists(e);
        if (ex instanceof FraudRuleViolationException e) return transaction.handleFraudRuleViolation(e);
        if (ex instanceof IdempotencyConflictException e) return transaction.handleIdempotencyConflict(e);
        if (ex instanceof PixCodeParseException e) return transaction.handlePixCodeParse(e);
        if (ex instanceof PvsAmountException e) return transaction.handlePvsAmount(e);
        if (ex instanceof PvsLimitExceededException e) return transaction.handlePvsLimitExceeded(e);
        // PSP
        if (ex instanceof PspTimeoutException e) return pixProvider.handlePspTimeout(e);
        // Merchant / conta / wallet / saque / billing
        if (ex instanceof MerchantNotFoundException e) return merchant.handleMerchantNotFound(e);
        if (ex instanceof MerchantAlreadyExistsException e) return merchant.handleMerchantAlreadyExists(e);
        if (ex instanceof MerchantNotActiveException e) return merchant.handleMerchantNotActive(e);
        if (ex instanceof MerchantLoginDeniedException e) return merchant.handleMerchantLoginDenied(e);
        if (ex instanceof MerchantAccessDeniedException e) return security.handleMerchantAccessDenied(e);
        if (ex instanceof InsufficientBalanceException e) return merchant.handleInsufficientBalance(e);
        if (ex instanceof WithdrawalsBlockedException e) return merchant.handleWithdrawalsBlocked(e);
        if (ex instanceof WithdrawalNotFoundException e) return merchant.handleWithdrawalNotFound(e);
        if (ex instanceof OutstandingDebtException e) return merchant.handleOutstandingDebt(e);
        if (ex instanceof InvoiceNotFoundException e) return merchant.handleInvoiceNotFound(e);
        if (ex instanceof ReceivableNotFoundException e) return merchant.handleReceivableNotFound(e);
        if (ex instanceof AuditReasonRequiredException e) return merchant.handleAuditReasonRequired(e);
        if (ex instanceof ApiKeyNotFoundException e) return merchant.handleApiKeyNotFound(e);
        if (ex instanceof TooManyApiKeysException e) return merchant.handleTooManyApiKeys(e);
        if (ex instanceof TooManyDestinationsException e) return merchant.handleTooManyDestinations(e);
        if (ex instanceof DestinationNotFoundException e) return merchant.handleDestinationNotFound(e);
        if (ex instanceof DestinationInactiveException e) return merchant.handleDestinationInactive(e);
        if (ex instanceof GiftPresetNotFoundException e) return merchant.handleGiftPresetNotFound(e);
        if (ex instanceof RiskSnapshotNotFoundException e) return merchant.handleRiskSnapshotNotFound(e);
        if (ex instanceof HoneypotBlockedException e) return merchant.handleHoneypotBlocked(e);
        if (ex instanceof WebhookNotFoundException e) return merchant.handleWebhookNotFound(e);
        if (ex instanceof InvalidWebhookEventException e) return merchant.handleInvalidWebhookEvent(e);
        // Loja white-label
        if (ex instanceof StoreNotFoundException e) return store.handleStoreNotFound(e);
        if (ex instanceof StoreAlreadyExistsException e) return store.handleStoreAlreadyExists(e);
        if (ex instanceof StoreButtonNotFoundException e) return store.handleStoreButtonNotFound(e);
        if (ex instanceof TooManyButtonsException e) return store.handleTooManyButtons(e);
        if (ex instanceof StoreMessageNotFoundException e) return store.handleStoreMessageNotFound(e);
        if (ex instanceof PlanNotFoundException e) return store.handlePlanNotFound(e);
        if (ex instanceof PlanInactiveException e) return store.handlePlanInactive(e);
        if (ex instanceof DeliveryNotFoundException e) return store.handleDeliveryNotFound(e);
        if (ex instanceof DeliveryActionNotFoundException e) return store.handleDeliveryActionNotFound(e);
        if (ex instanceof LeadNotFoundException e) return store.handleLeadNotFound(e);
        if (ex instanceof LeadBlockedException e) return store.handleLeadBlocked(e);
        if (ex instanceof MessageSendFailedException e) return store.handleMessageSendFailed(e);
        if (ex instanceof StoreBotNotFoundException e) return store.handleStoreBotNotFound(e);
        if (ex instanceof StoreBotAlreadyExistsException e) return store.handleStoreBotAlreadyExists(e);
        if (ex instanceof InvalidBotStateException e) return store.handleInvalidBotState(e);
        if (ex instanceof BotInactiveException e) return store.handleBotInactive(e);
        if (ex instanceof TelegramApiException e) return store.handleTelegramApi(e);
        if (ex instanceof DiscordApiException e) return store.handleDiscordApi(e);
        if (ex instanceof BroadcastNotFoundException e) return store.handleBroadcastNotFound(e);
        if (ex instanceof InvalidBroadcastStateException e) return store.handleInvalidBroadcastState(e);
        if (ex instanceof TrackingProfileNotFoundException e) return store.handleTrackingProfileNotFound(e);
        if (ex instanceof OperationProfileNotFoundException e) return store.handleOperationProfileNotFound(e);
        if (ex instanceof UnknownGatewayRouteException e) return store.handleUnknownGatewayRoute(e);
        if (ex instanceof InvalidProfileNameException e) return store.handleInvalidProfileName(e);
        if (ex instanceof InvalidProfileScopeException e) return store.handleInvalidProfileScope(e);
        if (ex instanceof RouteNotAllowedForScopeException e) return store.handleRouteNotAllowedForScope(e);
        if (ex instanceof BotsNotAllowedForScopeException e) return store.handleBotsNotAllowedForScope(e);
        // Auth / sessão / token / MFA / master
        if (ex instanceof InvalidCredentialsException e) return auth.handleInvalidCredentials(e);
        if (ex instanceof InvalidMfaCodeException e) return auth.handleInvalidMfaCode(e);
        if (ex instanceof InvalidResetTokenException e) return auth.handleInvalidResetToken(e);
        if (ex instanceof ResetTokenAlreadyOpenException e) return auth.handleResetTokenAlreadyOpen(e);
        if (ex instanceof TurnstileFailedException e) return auth.handleTurnstileFailed(e);
        if (ex instanceof MasterProfileAccessDeniedException e) return auth.handleMasterProfileAccessDenied(e);
        if (ex instanceof MasterNotFoundException e) return auth.handleMasterNotFound(e);
        if (ex instanceof TooManyMastersException e) return auth.handleTooManyMasters(e);
        if (ex instanceof MasterAlreadyExistsException e) return auth.handleMasterAlreadyExists(e);
        if (ex instanceof CannotDeactivateSelfException e) return auth.handleCannotDeactivateSelf(e);
        if (ex instanceof CannotDeactivateLastAdminException e) return auth.handleCannotDeactivateLastAdmin(e);
        // Security / acesso
        if (ex instanceof MasterPermissionDeniedException e) return security.handleMasterPermissionDenied(e);
        if (ex instanceof MasterStepUpRequiredException e) return security.handleMasterStepUpRequired(e);
        if (ex instanceof InvalidDocumentException e) return security.handleInvalidDocument(e);
        // Genérico / framework
        if (ex instanceof MissingServletRequestParameterException
                || ex instanceof MethodArgumentTypeMismatchException
                || ex instanceof HttpMessageNotReadableException
                || ex instanceof ConstraintViolationException) {
            return handler.handleBadRequest(ex);
        }
        if (ex instanceof HttpRequestMethodNotSupportedException e) return handler.handleMethodNotSupported(e);
        if (ex instanceof NoResourceFoundException e) return handler.handleNoResourceFound(e);
        if (ex instanceof IllegalStateException e) return handler.handleIllegalState(e);
        if (ex instanceof IllegalArgumentException e) return handler.handleIllegalArgument(e);
        if (ex instanceof MethodArgumentNotValidException e) return handler.handleValidation(e);
        return handler.handleUnexpected(ex, request);
    }

    @Test
    void contractMappingFrozenForAllHandlers() {
        List<String> failures = new ArrayList<>();
        for (Case c : CONTRACT) {
            try {
                ResponseEntity<ErrorResponse> response = invoke(c.factory().get(), new MockHttpServletRequest("GET", "/api/v1/test"));
                if (response.getStatusCode() != c.status() || response.getBody() == null || !response.getBody().code().equals(c.code())) {
                    failures.add(c.name() + ": esperado " + c.status().value() + "/" + c.code()
                            + " mas recebeu " + response.getStatusCode().value() + "/"
                            + (response.getBody() == null ? "null" : response.getBody().code()));
                }
            } catch (Exception e) {
                failures.add(c.name() + ": exceção inesperada " + e);
            }
        }
        assertThat(failures).as("Contrato de mapeamento de exceções (Spec 136 T0)").isEmpty();
    }

    @Test
    void unexpectedIncrements5xxMetricByRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/tx-1");
        invoke(new RuntimeException("boom"), request);
        assertThat(meterRegistry.get("foxcash_http_5xx_total")
                .tag("route", "/api/v1/transactions/tx-1").counter().count()).isEqualTo(1);
    }

    @Test
    void errorResponseBodyPreservesCodeAndMessage() {
        ResponseEntity<ErrorResponse> response = invoke(new MerchantNotFoundException(UUID.randomUUID()),
                new MockHttpServletRequest("GET", "/api/v1/test"));
        assertThat(response.getBody().code()).isEqualTo("merchant_not_found");
        assertThat(response.getBody().message()).isNotBlank();
    }
}
