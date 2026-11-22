package com.foxcash.infrastructure.inbound.rest.handler;

import com.foxcash.domain.exception.BotInactiveException;
import com.foxcash.domain.exception.BotsNotAllowedForScopeException;
import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.CommunityPostNotFoundException;
import com.foxcash.domain.exception.DeliveryActionNotFoundException;
import com.foxcash.domain.exception.DeliveryNotFoundException;
import com.foxcash.domain.exception.DiscordApiException;
import com.foxcash.domain.exception.InvalidBotStateException;
import com.foxcash.domain.exception.InvalidBroadcastStateException;
import com.foxcash.domain.exception.InvalidProfileNameException;
import com.foxcash.domain.exception.InvalidProfileScopeException;
import com.foxcash.domain.exception.LeadBlockedException;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.exception.MessageSendFailedException;
import com.foxcash.domain.exception.OperationProfileNotFoundException;
import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.exception.RouteNotAllowedForScopeException;
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
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Spec 136 (refactor de god classes) — especialista de domínio: loja white-label
 * (stores, botões, mensagens, planos, entregas, bots de venda, CRM/leads,
 * broadcasts, perfis de tracking e perfis de operação). Movido do
 * {@code GlobalExceptionHandler} sem mudança de comportamento (provado pelo
 * {@code ExceptionMappingContractTest}).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StoreExceptionHandler {

    @ExceptionHandler(StoreNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStoreNotFound(StoreNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("store_not_found", ex.getMessage()));
    }

    @ExceptionHandler(StoreAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleStoreAlreadyExists(StoreAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("store_already_exists", ex.getMessage()));
    }

    @ExceptionHandler(StoreButtonNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStoreButtonNotFound(StoreButtonNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("button_not_found", ex.getMessage()));
    }

    @ExceptionHandler(TooManyButtonsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyButtons(TooManyButtonsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("too_many_buttons", ex.getMessage()));
    }

    // ---- Spec 51: mensagens da loja ----

    @ExceptionHandler(StoreMessageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStoreMessageNotFound(StoreMessageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("message_not_found", ex.getMessage()));
    }

    // ---- Spec 83: CRM (leads e chat do portal) ----

    @ExceptionHandler(LeadNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleLeadNotFound(LeadNotFoundException ex) {
        // 404 cross-lojista (padrão Spec 83 C3 — sem revelar existência).
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("lead_not_found", ex.getMessage()));
    }

    @ExceptionHandler(LeadBlockedException.class)
    public ResponseEntity<ErrorResponse> handleLeadBlocked(LeadBlockedException ex) {
        // Envio recusado (Spec 83 D5/D6) — o lead bloqueou o bot (409).
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("lead_blocked", ex.getMessage()));
    }

    @ExceptionHandler(MessageSendFailedException.class)
    public ResponseEntity<ErrorResponse> handleMessageSendFailed(MessageSendFailedException ex) {
        // Provedor rejeitou o envio (Spec 83 D5) — 502 sem persistir como enviada.
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("message_send_failed", ex.getMessage()));
    }

    // ---- Spec 74: bots de venda ----

    @ExceptionHandler(StoreBotNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStoreBotNotFound(StoreBotNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("bot_not_found", ex.getMessage()));
    }

    @ExceptionHandler(StoreBotAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleStoreBotAlreadyExists(StoreBotAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("bot_already_exists", ex.getMessage()));
    }

    @ExceptionHandler(InvalidBotStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBotState(InvalidBotStateException ex) {
        // Transição inválida na máquina de estados do bot (Spec 74) — 409 sem
        // ecoar a mensagem interna (mesmo padrão do IllegalStateException).
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("invalid_bot_state", "Transicao de estado invalida para o bot"));
    }

    // Spec 75: compra de plano com botId em bot não-ACTIVE (PAUSED/ERROR/REGISTERING).
    @ExceptionHandler(BotInactiveException.class)
    public ResponseEntity<ErrorResponse> handleBotInactive(BotInactiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("bot_inactive", ex.getMessage()));
    }

    // Spec 77 (wizard): token inválido/expirado ou chamada rejeitada pela Bot
    // API do Telegram (validate/setup) — 400 com a mensagem da API (o lojista
    // precisa saber que o token está errado). No registro (POST /bots) a falha
    // é capturada pela aplicação (bot ERROR + auditoria), então este handler só
    // alcança os endpoints stateless do wizard.
    @ExceptionHandler(TelegramApiException.class)
    public ResponseEntity<ErrorResponse> handleTelegramApi(TelegramApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("telegram_api_error", ex.getMessage()));
    }

    // Spec 78 (wizard): mesma regra da Spec 77 para o Discord — token inválido ou
    // chamada rejeitada pela API (validate/setup stateless) → 400 com a mensagem
    // da API. No registro (POST /bots) a falha é capturada pela aplicação
    // (bot ERROR + auditoria), então este handler só alcança os endpoints do wizard.
    @ExceptionHandler(DiscordApiException.class)
    public ResponseEntity<ErrorResponse> handleDiscordApi(DiscordApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("discord_api_error", ex.getMessage()));
    }

    // ---- Spec 79: broadcasts (portal + API do bot) ----

    @ExceptionHandler(BroadcastNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBroadcastNotFound(BroadcastNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("broadcast_not_found", ex.getMessage()));
    }

    /** Force-cancel do master em broadcast não-PENDING (Spec 89) — 409. */
    @ExceptionHandler(InvalidBroadcastStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBroadcastState(InvalidBroadcastStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("invalid_broadcast_state", ex.getMessage()));
    }

    // ---- Spec 119: Comunidade (portal + master) ----

    @ExceptionHandler(CommunityPostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCommunityPostNotFound(CommunityPostNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("community_post_not_found", ex.getMessage()));
    }

    // ---- Spec 81: perfis de tracking (portal) ----

    @ExceptionHandler(TrackingProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTrackingProfileNotFound(TrackingProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("tracking_profile_not_found", ex.getMessage()));
    }

    // ---- Spec 122: perfis de operação do portal ----

    @ExceptionHandler(OperationProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOperationProfileNotFound(OperationProfileNotFoundException ex) {
        // 404 cross-lojista (padrão IDOR — sem revelar existência).
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("profile_not_found", ex.getMessage()));
    }

    @ExceptionHandler(UnknownGatewayRouteException.class)
    public ResponseEntity<ErrorResponse> handleUnknownGatewayRoute(UnknownGatewayRouteException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("unknown_gateway_route", ex.getMessage()));
    }

    @ExceptionHandler(InvalidProfileNameException.class)
    public ResponseEntity<ErrorResponse> handleInvalidProfileName(InvalidProfileNameException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_name", ex.getMessage()));
    }

    // ---- Spec 139: escopo do perfil de operação ----

    @ExceptionHandler(InvalidProfileScopeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidProfileScope(InvalidProfileScopeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("invalid_scope", ex.getMessage()));
    }

    @ExceptionHandler(RouteNotAllowedForScopeException.class)
    public ResponseEntity<ErrorResponse> handleRouteNotAllowedForScope(RouteNotAllowedForScopeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("route_not_allowed_for_scope", ex.getMessage()));
    }

    @ExceptionHandler(BotsNotAllowedForScopeException.class)
    public ResponseEntity<ErrorResponse> handleBotsNotAllowedForScope(BotsNotAllowedForScopeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("bots_not_allowed_for_scope", ex.getMessage()));
    }

    // ---- Spec 49: planos, ações de entrega e entregas ----

    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePlanNotFound(PlanNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("plan_not_found", ex.getMessage()));
    }

    @ExceptionHandler(PlanInactiveException.class)
    public ResponseEntity<ErrorResponse> handlePlanInactive(PlanInactiveException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("plan_inactive", ex.getMessage()));
    }

    @ExceptionHandler(DeliveryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDeliveryNotFound(DeliveryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("delivery_not_found", ex.getMessage()));
    }

    @ExceptionHandler(DeliveryActionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDeliveryActionNotFound(DeliveryActionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("delivery_action_not_found", ex.getMessage()));
    }
}
