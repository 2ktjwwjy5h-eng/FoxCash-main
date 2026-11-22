package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.BotInactiveException;
import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.DeliveryActionNotFoundException;
import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.DiscordApiException;
import com.foxcash.domain.exception.InvalidBotStateException;
import com.foxcash.domain.exception.InvalidBroadcastStateException;
import com.foxcash.domain.exception.InvalidProfileNameException;
import com.foxcash.domain.exception.LeadBlockedException;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.exception.MessageSendFailedException;
import com.foxcash.domain.exception.OperationProfileNotFoundException;
import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.StoreAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.exception.StoreButtonNotFoundException;
import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.exception.TelegramApiException;
import com.foxcash.domain.exception.TooManyButtonsException;
import com.foxcash.domain.exception.TrackingProfileNotFoundException;
import com.foxcash.domain.exception.UnknownGatewayRouteException;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 136 (C4) — teste isolado do {@link StoreExceptionHandler}:
 * exceção X → status + code (mesma semântica do contrato congelado em
 * {@code ExceptionMappingContractTest}).
 */
class StoreExceptionHandlerTest {

    private final StoreExceptionHandler handler = new StoreExceptionHandler();

    private void assertMapping(ResponseEntity<ErrorResponse> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
    }

    @Test
    void storeNotFoundMapsTo404() {
        assertMapping(handler.handleStoreNotFound(new StoreNotFoundException()), 404, "store_not_found");
    }

    @Test
    void storeAlreadyExistsMapsTo409() {
        assertMapping(handler.handleStoreAlreadyExists(new StoreAlreadyExistsException()), 409, "store_already_exists");
    }

    @Test
    void storeButtonNotFoundMapsTo404() {
        assertMapping(handler.handleStoreButtonNotFound(new StoreButtonNotFoundException()), 404, "button_not_found");
    }

    @Test
    void tooManyButtonsMapsTo409() {
        assertMapping(handler.handleTooManyButtons(new TooManyButtonsException()), 409, "too_many_buttons");
    }

    @Test
    void storeMessageNotFoundMapsTo404() {
        assertMapping(handler.handleStoreMessageNotFound(new StoreMessageNotFoundException()), 404, "message_not_found");
    }

    @Test
    void planNotFoundMapsTo404() {
        assertMapping(handler.handlePlanNotFound(new PlanNotFoundException()), 404, "plan_not_found");
    }

    @Test
    void planInactiveMapsTo409() {
        assertMapping(handler.handlePlanInactive(new PlanInactiveException()), 409, "plan_inactive");
    }

    @Test
    void deliveryNotFoundMapsTo404() {
        assertMapping(handler.handleDeliveryNotFound(new DeliveryNotFoundException()), 404, "delivery_not_found");
    }

    @Test
    void deliveryActionNotFoundMapsTo404() {
        assertMapping(handler.handleDeliveryActionNotFound(new DeliveryActionNotFoundException()),
                404, "delivery_action_not_found");
    }

    @Test
    void leadNotFoundMapsTo404() {
        assertMapping(handler.handleLeadNotFound(new LeadNotFoundException("lead")), 404, "lead_not_found");
    }

    @Test
    void leadBlockedMapsTo409() {
        assertMapping(handler.handleLeadBlocked(new LeadBlockedException("blocked")), 409, "lead_blocked");
    }

    @Test
    void messageSendFailedMapsTo502() {
        assertMapping(handler.handleMessageSendFailed(
                        new MessageSendFailedException("fail", new RuntimeException())),
                502, "message_send_failed");
    }

    @Test
    void storeBotNotFoundMapsTo404() {
        assertMapping(handler.handleStoreBotNotFound(new StoreBotNotFoundException()), 404, "bot_not_found");
    }

    @Test
    void storeBotAlreadyExistsMapsTo409() {
        assertMapping(handler.handleStoreBotAlreadyExists(new StoreBotAlreadyExistsException()), 409, "bot_already_exists");
    }

    @Test
    void invalidBotStateMapsTo409() {
        assertMapping(handler.handleInvalidBotState(new InvalidBotStateException("x")), 409, "invalid_bot_state");
    }

    @Test
    void botInactiveMapsTo409() {
        assertMapping(handler.handleBotInactive(new BotInactiveException()), 409, "bot_inactive");
    }

    @Test
    void telegramApiMapsTo400() {
        assertMapping(handler.handleTelegramApi(new TelegramApiException("x")), 400, "telegram_api_error");
    }

    @Test
    void discordApiMapsTo400() {
        assertMapping(handler.handleDiscordApi(new DiscordApiException("x")), 400, "discord_api_error");
    }

    @Test
    void broadcastNotFoundMapsTo404() {
        assertMapping(handler.handleBroadcastNotFound(new BroadcastNotFoundException()), 404, "broadcast_not_found");
    }

    @Test
    void invalidBroadcastStateMapsTo409() {
        assertMapping(handler.handleInvalidBroadcastState(new InvalidBroadcastStateException()),
                409, "invalid_broadcast_state");
    }

    @Test
    void trackingProfileNotFoundMapsTo404() {
        assertMapping(handler.handleTrackingProfileNotFound(new TrackingProfileNotFoundException()),
                404, "tracking_profile_not_found");
    }

    @Test
    void operationProfileNotFoundMapsTo404() {
        assertMapping(handler.handleOperationProfileNotFound(new OperationProfileNotFoundException("x")),
                404, "profile_not_found");
    }

    @Test
    void unknownGatewayRouteMapsTo400() {
        assertMapping(handler.handleUnknownGatewayRoute(new UnknownGatewayRouteException("x")),
                400, "unknown_gateway_route");
    }

    @Test
    void invalidProfileNameMapsTo400() {
        assertMapping(handler.handleInvalidProfileName(new InvalidProfileNameException("x")), 400, "invalid_name");
    }
}
